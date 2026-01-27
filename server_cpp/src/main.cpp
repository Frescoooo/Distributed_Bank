#include "protocol.hpp"
#include "bank.hpp"

#include <winsock2.h>
#include <ws2tcpip.h>

// g++/MinGW 不需要 #pragma comment(lib,...)
// #pragma comment(lib, "ws2_32.lib")

#include <algorithm>   // for std::remove_if
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <random>
#include <string>
#include <unordered_map>
#include <vector>




struct MonitorEntry {
  sockaddr_in addr{};
  std::chrono::steady_clock::time_point expireAt;
};

static std::string addrToKey(const sockaddr_in& a) {
  // inet_ntoa returns a pointer to a static buffer; copy immediately.
  const char* ip = inet_ntoa(a.sin_addr);
  std::string ipStr = (ip ? std::string(ip) : std::string("0.0.0.0"));
  return ipStr + ":" + std::to_string(ntohs(a.sin_port));
}

struct DedupEntry {
  std::vector<uint8_t> replyBytes;
  std::chrono::steady_clock::time_point expireAt;
};

static void usage() {
  std::cout << "Usage: udp_server.exe --port 9000 --lossReq 0.0 --lossRep 0.0\n";
}

int main(int argc, char** argv) {
  int port = 9000;
  double lossReq = 0.0;
  double lossRep = 0.0;

  for (int i = 1; i < argc; i++) {
    std::string a = argv[i];
    if (a == "--port" && i + 1 < argc) port = std::atoi(argv[++i]);
    else if (a == "--lossReq" && i + 1 < argc) lossReq = std::atof(argv[++i]);
    else if (a == "--lossRep" && i + 1 < argc) lossRep = std::atof(argv[++i]);
    else { usage(); return 1; }
  }

  WSADATA wsa{};
  if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
    std::cerr << "WSAStartup failed\n";
    return 1;
  }

  SOCKET s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
  if (s == INVALID_SOCKET) {
    std::cerr << "socket() failed\n";
    WSACleanup();
    return 1;
  }

  sockaddr_in serverAddr{};
  serverAddr.sin_family = AF_INET;
  serverAddr.sin_addr.s_addr = INADDR_ANY;
  serverAddr.sin_port = htons((u_short)port);

  if (bind(s, (sockaddr*)&serverAddr, sizeof(serverAddr)) == SOCKET_ERROR) {
    std::cerr << "bind() failed\n";
    closesocket(s);
    WSACleanup();
    return 1;
  }

  std::cout << "[server] UDP listening on port " << port
            << " lossReq=" << lossReq << " lossRep=" << lossRep << "\n";

  Bank bank;

  std::mt19937 rng((unsigned)std::chrono::high_resolution_clock::now().time_since_epoch().count());
  std::uniform_real_distribution<double> uni(0.0, 1.0);

  std::vector<MonitorEntry> monitors;
  std::unordered_map<std::string, DedupEntry> dedup; // key=addrKey + "#" + requestId

  auto now = [] { return std::chrono::steady_clock::now(); };

  while (true) {
    // periodic cleanup
    auto t = now();
    monitors.erase(std::remove_if(monitors.begin(), monitors.end(),
                    [&](const MonitorEntry& e){ return e.expireAt <= t; }),
                  monitors.end());
    for (auto it = dedup.begin(); it != dedup.end(); ) {
      if (it->second.expireAt <= t) it = dedup.erase(it);
      else ++it;
    }

    // recv
    std::vector<uint8_t> buf(2048);
    sockaddr_in clientAddr{};
    int clientLen = sizeof(clientAddr);
    int n = recvfrom(s, (char*)buf.data(), (int)buf.size(), 0, (sockaddr*)&clientAddr, &clientLen);
    if (n == SOCKET_ERROR) continue;
    buf.resize(n);

    std::string clientKey = addrToKey(clientAddr);

    if (uni(rng) < lossReq) {
      std::cout << "[server] DROP request from " << clientKey << " (simulated)\n";
      continue;
    }

    proto::Message req;
    if (!proto::decode(buf, req) || req.h.version != proto::VERSION || req.h.msgType != (uint8_t)proto::MsgType::Request) {
      std::cout << "[server] Bad request from " << clientKey << "\n";
      continue;
    }

    bool atMostOnce = (req.h.flags & proto::FLAG_AT_MOST_ONCE) != 0;
    std::string dedupKey = clientKey + "#" + std::to_string((unsigned long long)req.h.requestId);

    if (atMostOnce) {
      auto it = dedup.find(dedupKey);
      if (it != dedup.end()) {
        std::cout << "[server] DUP reqId=" << (unsigned long long)req.h.requestId
                  << " from " << clientKey << " => replay cached reply\n";
        if (uni(rng) < lossRep) {
          std::cout << "[server] DROP reply (simulated)\n";
        } else {
          sendto(s, (const char*)it->second.replyBytes.data(), (int)it->second.replyBytes.size(), 0,
                 (sockaddr*)&clientAddr, clientLen);
        }
        continue;
      }
    }

    std::cout << "[server] recv op=" << req.h.opCode << " reqId=" << (unsigned long long)req.h.requestId
              << " from " << clientKey << " flags=" << req.h.flags << "\n";

    proto::Message rep;
    rep.h.magic = proto::MAGIC;
    rep.h.version = proto::VERSION;
    rep.h.msgType = (uint8_t)proto::MsgType::Reply;
    rep.h.opCode = req.h.opCode;
    rep.h.flags = req.h.flags;
    rep.h.status = (uint16_t)proto::Status::OK;
    rep.h.requestId = req.h.requestId;

    // handle ops
    if (req.h.opCode == (uint16_t)proto::OpCode::OPEN) {
      size_t off = 0;
      std::string name, pw;
      uint16_t curU16;
      double initial;
      if (!proto::getString(req.body, off, name) ||
          !proto::getPassword16(req.body, off, pw) ||
          !proto::getU16(req.body, off, curU16) ||
          !proto::getDouble(req.body, off, initial)) {
        rep.h.status = (uint16_t)proto::Status::ERR_BAD_REQUEST;
      } else {
        int32_t accNo; double bal; proto::Status err;
        if (!bank.openAccount(name, pw, (proto::Currency)curU16, initial, accNo, bal, err)) {
          rep.h.status = (uint16_t)err;
        } else {
          proto::putI32(rep.body, accNo);
          proto::putDouble(rep.body, bal);

          // callback: OPEN event
          for (const auto& m : monitors) {
            proto::Message cb;
            cb.h.magic = proto::MAGIC;
            cb.h.version = proto::VERSION;
            cb.h.msgType = (uint8_t)proto::MsgType::Callback;
            cb.h.opCode = (uint16_t)proto::OpCode::CALLBACK_UPDATE;
            cb.h.flags = 0;
            cb.h.status = 0;
            cb.h.requestId = 0;

            proto::putU16(cb.body, (uint16_t)proto::OpCode::OPEN); // updateType
            proto::putI32(cb.body, accNo);
            proto::putU16(cb.body, curU16);
            proto::putDouble(cb.body, bal);
            proto::putString(cb.body, "OPEN by " + name);
            cb.h.bodyLen = (uint32_t)cb.body.size();
            auto bytes = proto::encode(cb);
            sendto(s, (const char*)bytes.data(), (int)bytes.size(), 0, (sockaddr*)&m.addr, sizeof(m.addr));
          }
        }
      }
    } else if (req.h.opCode == (uint16_t)proto::OpCode::QUERY_BALANCE) {
      size_t off = 0;
      std::string name, pw;
      int32_t accNo;
      if (!proto::getString(req.body, off, name) ||
          !proto::getI32(req.body, off, accNo) ||
          !proto::getPassword16(req.body, off, pw)) {
        rep.h.status = (uint16_t)proto::Status::ERR_BAD_REQUEST;
      } else {
        proto::Currency cur; double bal; proto::Status err;
        if (!bank.queryBalance(name, accNo, pw, cur, bal, err)) {
          rep.h.status = (uint16_t)err;
        } else {
          proto::putU16(rep.body, (uint16_t)cur);
          proto::putDouble(rep.body, bal);
        }
      }
    } else if (req.h.opCode == (uint16_t)proto::OpCode::MONITOR_REGISTER) {
      size_t off = 0;
      uint16_t seconds;
      if (!proto::getU16(req.body, off, seconds)) {
        rep.h.status = (uint16_t)proto::Status::ERR_BAD_REQUEST;
      } else {
        MonitorEntry e;
        e.addr = clientAddr;
        e.expireAt = now() + std::chrono::seconds(seconds);
        monitors.push_back(e);
        proto::putString(rep.body, "monitor registered for " + std::to_string(seconds) + "s");
        std::cout << "[server] monitor add " << clientKey << " for " << seconds << "s\n";
      }
    } else {
      rep.h.status = (uint16_t)proto::Status::ERR_BAD_REQUEST;
    }

    rep.h.bodyLen = (uint32_t)rep.body.size();
    auto repBytes = proto::encode(rep);

    if (atMostOnce) {
      DedupEntry de;
      de.replyBytes = repBytes;
      de.expireAt = now() + std::chrono::seconds(60); // simplest
      dedup[dedupKey] = std::move(de);
    }

    if (uni(rng) < lossRep) {
      std::cout << "[server] DROP reply to " << clientKey << " (simulated)\n";
      continue;
    }

    sendto(s, (const char*)repBytes.data(), (int)repBytes.size(), 0, (sockaddr*)&clientAddr, clientLen);
  }
}