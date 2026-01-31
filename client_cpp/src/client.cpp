#include "client.hpp"
#include <iostream>
#include <chrono>
#include <random>
#include <thread>
#include <cstring>

#ifdef _WIN32
#pragma comment(lib, "ws2_32.lib")
#endif

Client::Client(const std::string& serverIp, int serverPort, bool atMostOnce, int timeoutMs, int retryCount)
    : serverIp_(serverIp), serverPort_(serverPort), atMostOnce_(atMostOnce),
      timeoutMs_(timeoutMs), retryCount_(retryCount), nextRequestId_(1) {
#ifdef _WIN32
    sock_ = INVALID_SOCKET;
#else
    sock_ = -1;
#endif
}

Client::~Client() {
#ifdef _WIN32
    if (sock_ != INVALID_SOCKET) {
        closesocket(sock_);
    }
    WSACleanup();
#else
    if (sock_ >= 0) {
        close(sock_);
    }
#endif
}

bool Client::init() {
#ifdef _WIN32
    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
        std::cerr << "[client] WSAStartup failed\n";
        return false;
    }
#endif

    sock_ = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
#ifdef _WIN32
    if (sock_ == INVALID_SOCKET) {
#else
    if (sock_ < 0) {
#endif
        std::cerr << "[client] socket() failed\n";
        return false;
    }

    memset(&serverAddr_, 0, sizeof(serverAddr_));
    serverAddr_.sin_family = AF_INET;
    serverAddr_.sin_port = htons((uint16_t)serverPort_);
    
#ifdef _WIN32
    serverAddr_.sin_addr.s_addr = inet_addr(serverIp_.c_str());
    if (serverAddr_.sin_addr.s_addr == INADDR_NONE) {
#else
    if (inet_pton(AF_INET, serverIp_.c_str(), &serverAddr_.sin_addr) <= 0) {
#endif
        std::cerr << "[client] Invalid server IP: " << serverIp_ << "\n";
        return false;
    }

    std::cout << "[client] server=" << serverIp_ << ":" << serverPort_
              << " sem=" << (atMostOnce_ ? "at-most-once" : "at-least-once")
              << " timeout=" << timeoutMs_ << "ms retry=" << retryCount_ << "\n";

    return true;
}

bool Client::call(uint16_t opCode, const std::vector<uint8_t>& body, proto::Message& reply) {
    // Generate random request ID
    static std::mt19937_64 rng(std::chrono::high_resolution_clock::now().time_since_epoch().count());
    uint64_t reqId = rng();

    // Build request message
    proto::Message req;
    req.h.magic = proto::MAGIC;
    req.h.version = proto::VERSION;
    req.h.msgType = (uint8_t)proto::MsgType::Request;
    req.h.opCode = opCode;
    req.h.flags = atMostOnce_ ? proto::FLAG_AT_MOST_ONCE : 0;
    req.h.status = 0;
    req.h.requestId = reqId;
    req.h.bodyLen = (uint32_t)body.size();
    req.body = body;

    auto reqBytes = proto::encode(req);

    std::cout << "[client] sending op=" << proto::opCodeToString(opCode) 
              << " bodyLen=" << body.size() << " totalLen=" << reqBytes.size() << "\n";

    // Set socket timeout
#ifdef _WIN32
    DWORD tv = timeoutMs_;
    setsockopt(sock_, SOL_SOCKET, SO_RCVTIMEO, (const char*)&tv, sizeof(tv));
#else
    struct timeval tv;
    tv.tv_sec = timeoutMs_ / 1000;
    tv.tv_usec = (timeoutMs_ % 1000) * 1000;
    setsockopt(sock_, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
#endif

    for (int attempt = 1; attempt <= retryCount_; attempt++) {
        // Send request
        int sent = sendto(sock_, (const char*)reqBytes.data(), (int)reqBytes.size(), 0,
                         (sockaddr*)&serverAddr_, sizeof(serverAddr_));
        if (sent < 0) {
            std::cerr << "[client] sendto() failed\n";
            continue;
        }

        // Receive reply
        std::vector<uint8_t> buf(2048);
        sockaddr_in fromAddr;
#ifdef _WIN32
        int fromLen = sizeof(fromAddr);
#else
        socklen_t fromLen = sizeof(fromAddr);
#endif

        int n = recvfrom(sock_, (char*)buf.data(), (int)buf.size(), 0,
                        (sockaddr*)&fromAddr, &fromLen);

        if (n < 0) {
#ifdef _WIN32
            int err = WSAGetLastError();
            if (err == WSAETIMEDOUT) {
#else
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
#endif
                std::cout << "[client] timeout, retry " << attempt << "/" << retryCount_ << "\n";
                continue;
            }
            std::cerr << "[client] recvfrom() failed\n";
            continue;
        }

        buf.resize(n);
        if (!proto::decode(buf, reply)) {
            std::cout << "[client] decode() failed, retry " << attempt << "/" << retryCount_ << "\n";
            continue;
        }

        if (reply.h.msgType != (uint8_t)proto::MsgType::Reply) {
            std::cout << "[client] ignore non-reply msgType=" << (int)reply.h.msgType << "\n";
            continue;
        }

        if (reply.h.requestId != reqId) {
            std::cout << "[client] ignore reply for other reqId=" << reply.h.requestId 
                      << " (expect " << reqId << ")\n";
            continue;
        }

        std::cout << "[client] got reply ok: op=" << proto::opCodeToString(reply.h.opCode)
                  << " status=" << proto::statusToString(reply.h.status)
                  << " reqId=" << reply.h.requestId << "\n";
        return true;
    }

    std::cerr << "[client] request failed after " << retryCount_ << " retries\n";
    return false;
}

void Client::clearScreen() {
    // Print newlines to simulate clear
    for (int i = 0; i < 50; ++i) std::cout << "\n";
}

std::string Client::readLine(const std::string& prompt) {
    std::cout << prompt;
    std::string line;
    std::getline(std::cin, line);
    return line;
}

std::string Client::readPassword(const std::string& prompt) {
    // In a real implementation, you might want to hide password input
    return readLine(prompt);
}

bool Client::readInt(const std::string& prompt, int& out) {
    std::string line = readLine(prompt);
    if (line.empty() || line == "q" || line == "Q") return false;
    try {
        out = std::stoi(line);
        return true;
    } catch (...) {
        std::cout << "Invalid number\n";
        return false;
    }
}

bool Client::readDouble(const std::string& prompt, double& out) {
    std::string line = readLine(prompt);
    if (line.empty() || line == "q" || line == "Q") return false;
    try {
        out = std::stod(line);
        return true;
    } catch (...) {
        std::cout << "Invalid number\n";
        return false;
    }
}

uint16_t Client::readCurrency() {
    while (true) {
        std::string cur = readLine("currency (CNY/SGD, or 'q' to cancel): ");
        if (cur == "q" || cur == "Q") return 0xFFFF;
        if (cur == "CNY" || cur == "cny") return (uint16_t)proto::Currency::CNY;
        if (cur == "SGD" || cur == "sgd") return (uint16_t)proto::Currency::SGD;
        std::cout << "Invalid currency. Please enter CNY or SGD.\n";
    }
}

void Client::run() {
    while (true) {
        std::cout << "\n== Menu ==\n";
        std::cout << "1) OPEN account\n";
        std::cout << "2) CLOSE account\n";
        std::cout << "3) DEPOSIT (non-idempotent)\n";
        std::cout << "4) WITHDRAW (non-idempotent)\n";
        std::cout << "5) QUERY balance (idempotent)\n";
        std::cout << "6) TRANSFER (non-idempotent)\n";
        std::cout << "7) MONITOR register (callback)\n";
        std::cout << "0) EXIT\n";
        std::cout << "Choose: ";

        std::string choice;
        std::getline(std::cin, choice);

        if (choice == "0") {
            std::cout << "Bye.\n";
            break;
        } else if (choice == "1") {
            handleOpen();
        } else if (choice == "2") {
            handleClose();
        } else if (choice == "3") {
            handleDeposit();
        } else if (choice == "4") {
            handleWithdraw();
        } else if (choice == "5") {
            handleQueryBalance();
        } else if (choice == "6") {
            handleTransfer();
        } else if (choice == "7") {
            handleMonitor();
        } else {
            std::cout << "Unknown option\n";
        }
    }
}

void Client::handleOpen() {
    clearScreen();
    std::cout << "=== OPEN Account (enter 'q' at any prompt to cancel) ===\n";

    std::string name = readLine("name (or 'q' to cancel): ");
    if (name.empty() || name == "q" || name == "Q") return;

    std::string password;
    while (true) {
        password = readPassword("password (1..16 chars, or 'q' to cancel): ");
        if (password == "q" || password == "Q") return;
        if (password.empty() || password.length() > 16) {
            std::cout << "Password must be 1-16 characters.\n";
            continue;
        }
        std::string confirm = readPassword("confirm password: ");
        if (confirm == "q" || confirm == "Q") return;
        if (password != confirm) {
            std::cout << "Passwords do not match. Try again.\n";
            continue;
        }
        break;
    }

    uint16_t currency = readCurrency();
    if (currency == 0xFFFF) return;

    double initialBalance;
    if (!readDouble("initial balance (or 'q' to cancel): ", initialBalance)) return;
    if (initialBalance < 0) {
        std::cout << "Balance cannot be negative.\n";
        return;
    }

    // Build request body
    std::vector<uint8_t> body;
    proto::putString(body, name);
    proto::putPassword16(body, password);
    proto::putU16(body, currency);
    proto::putDouble(body, initialBalance);

    proto::Message reply;
    if (!call((uint16_t)proto::OpCode::OPEN, body, reply)) {
        std::cout << "Network error: No reply from server (UDP packet loss/wrong port/server not running/firewall). Returned to menu.\n";
        readLine("Press Enter to continue...");
        return;
    }

    if (reply.h.status != (uint16_t)proto::Status::OK) {
        std::cout << "OPEN failed, status=" << proto::statusToString(reply.h.status) << "\n";
        readLine("Press Enter to continue...");
        return;
    }

    size_t off = 0;
    int32_t accNo;
    double bal;
    if (proto::getI32(reply.body, off, accNo) && proto::getDouble(reply.body, off, bal)) {
        std::cout << "OPEN OK. accountNo=" << accNo << " balance=" << bal << "\n";
    }
    readLine("Press Enter to continue...");
}

void Client::handleClose() {
    clearScreen();
    std::cout << "=== CLOSE Account (enter 'q' at any prompt to cancel) ===\n";

    std::string name = readLine("name (or 'q' to cancel): ");
    if (name.empty() || name == "q" || name == "Q") return;

    int accNo;
    if (!readInt("accountNo (or 'q' to cancel): ", accNo)) return;
    if (accNo <= 0) {
        std::cout << "Account number must be positive.\n";
        return;
    }

    std::string password = readPassword("password (or 'q' to cancel): ");
    if (password == "q" || password == "Q") return;

    std::vector<uint8_t> body;
    proto::putString(body, name);
    proto::putI32(body, accNo);
    proto::putPassword16(body, password);

    proto::Message reply;
    if (!call((uint16_t)proto::OpCode::CLOSE, body, reply)) {
        std::cout << "CLOSE failed: communication error\n";
        readLine("Press Enter to continue...");
        return;
    }

    if (reply.h.status != (uint16_t)proto::Status::OK) {
        std::cout << "CLOSE failed, status=" << proto::statusToString(reply.h.status) << "\n";
        readLine("Press Enter to continue...");
        return;
    }

    size_t off = 0;
    std::string msg;
    if (proto::getString(reply.body, off, msg)) {
        std::cout << "CLOSE OK: " << msg << "\n";
    }
    readLine("Press Enter to continue...");
}

void Client::handleDeposit() {
    clearScreen();
    std::cout << "=== DEPOSIT (enter 'q' at any prompt to cancel) ===\n";

    std::string name = readLine("name (or 'q' to cancel): ");
    if (name.empty() || name == "q" || name == "Q") return;

    int accNo;
    if (!readInt("accountNo (or 'q' to cancel): ", accNo)) return;
    if (accNo <= 0) {
        std::cout << "Account number must be positive.\n";
        return;
    }

    std::string password = readPassword("password (or 'q' to cancel): ");
    if (password == "q" || password == "Q") return;

    uint16_t currency = readCurrency();
    if (currency == 0xFFFF) return;

    double amount;
    if (!readDouble("amount (or 'q' to cancel): ", amount)) return;
    if (amount <= 0) {
        std::cout << "Amount must be positive.\n";
        return;
    }

    std::vector<uint8_t> body;
    proto::putString(body, name);
    proto::putI32(body, accNo);
    proto::putPassword16(body, password);
    proto::putU16(body, currency);
    proto::putDouble(body, amount);

    proto::Message reply;
    if (!call((uint16_t)proto::OpCode::DEPOSIT, body, reply)) {
        std::cout << "DEPOSIT failed: communication error\n";
        readLine("Press Enter to continue...");
        return;
    }

    if (reply.h.status != (uint16_t)proto::Status::OK) {
        std::cout << "DEPOSIT failed, status=" << proto::statusToString(reply.h.status) << "\n";
        readLine("Press Enter to continue...");
        return;
    }

    size_t off = 0;
    double newBal;
    if (proto::getDouble(reply.body, off, newBal)) {
        std::cout << "Password & account verified. Hello, " << name << "!\n";
        std::cout << "DEPOSIT OK. new balance=" << newBal << "\n";
    }
    readLine("Press Enter to continue...");
}

void Client::handleWithdraw() {
    clearScreen();
    std::cout << "=== WITHDRAW (enter 'q' at any prompt to cancel) ===\n";

    std::string name = readLine("name (or 'q' to cancel): ");
    if (name.empty() || name == "q" || name == "Q") return;

    int accNo;
    if (!readInt("accountNo (or 'q' to cancel): ", accNo)) return;
    if (accNo <= 0) {
        std::cout << "Account number must be positive.\n";
        return;
    }

    std::string password = readPassword("password (or 'q' to cancel): ");
    if (password == "q" || password == "Q") return;

    uint16_t currency = readCurrency();
    if (currency == 0xFFFF) return;

    double amount;
    if (!readDouble("amount (or 'q' to cancel): ", amount)) return;
    if (amount <= 0) {
        std::cout << "Amount must be positive.\n";
        return;
    }

    std::vector<uint8_t> body;
    proto::putString(body, name);
    proto::putI32(body, accNo);
    proto::putPassword16(body, password);
    proto::putU16(body, currency);
    proto::putDouble(body, amount);

    proto::Message reply;
    if (!call((uint16_t)proto::OpCode::WITHDRAW, body, reply)) {
        std::cout << "WITHDRAW failed: communication error\n";
        readLine("Press Enter to continue...");
        return;
    }

    if (reply.h.status != (uint16_t)proto::Status::OK) {
        std::cout << "WITHDRAW failed, status=" << proto::statusToString(reply.h.status) << "\n";
        readLine("Press Enter to continue...");
        return;
    }

    size_t off = 0;
    double newBal;
    if (proto::getDouble(reply.body, off, newBal)) {
        std::cout << "Password & account verified. Hello, " << name << "!\n";
        std::cout << "WITHDRAW OK. new balance=" << newBal << "\n";
    }
    readLine("Press Enter to continue...");
}

void Client::handleQueryBalance() {
    clearScreen();
    std::cout << "=== QUERY Balance (enter 'q' at any prompt to cancel) ===\n";

    std::string name = readLine("name (or 'q' to cancel): ");
    if (name.empty() || name == "q" || name == "Q") return;

    int accNo;
    if (!readInt("accountNo (or 'q' to cancel): ", accNo)) return;
    if (accNo <= 0) {
        std::cout << "Account number must be positive.\n";
        return;
    }

    std::string password = readPassword("password (or 'q' to cancel): ");
    if (password == "q" || password == "Q") return;

    std::vector<uint8_t> body;
    proto::putString(body, name);
    proto::putI32(body, accNo);
    proto::putPassword16(body, password);

    proto::Message reply;
    if (!call((uint16_t)proto::OpCode::QUERY_BALANCE, body, reply)) {
        std::cout << "QUERY failed: communication error\n";
        readLine("Press Enter to continue...");
        return;
    }

    if (reply.h.status != (uint16_t)proto::Status::OK) {
        std::cout << "QUERY failed, status=" << proto::statusToString(reply.h.status) << "\n";
        readLine("Press Enter to continue...");
        return;
    }

    size_t off = 0;
    uint16_t cur;
    double bal;
    if (proto::getU16(reply.body, off, cur) && proto::getDouble(reply.body, off, bal)) {
        std::cout << "Password & account verified. Hello, " << name << "!\n";
        std::cout << "BALANCE: " << bal << " " << proto::currencyToString(cur) << "\n";
    }
    readLine("Press Enter to continue...");
}

void Client::handleTransfer() {
    clearScreen();
    std::cout << "=== TRANSFER (enter 'q' at any prompt to cancel) ===\n";

    std::string name = readLine("name (owner of FROM account, or 'q' to cancel): ");
    if (name.empty() || name == "q" || name == "Q") return;

    int fromAccNo;
    if (!readInt("fromAccountNo (or 'q' to cancel): ", fromAccNo)) return;
    if (fromAccNo <= 0) {
        std::cout << "Account number must be positive.\n";
        return;
    }

    std::string password = readPassword("password (or 'q' to cancel): ");
    if (password == "q" || password == "Q") return;

    int toAccNo;
    if (!readInt("toAccountNo (or 'q' to cancel): ", toAccNo)) return;
    if (toAccNo <= 0) {
        std::cout << "Account number must be positive.\n";
        return;
    }
    if (fromAccNo == toAccNo) {
        std::cout << "Cannot transfer to the same account.\n";
        return;
    }

    uint16_t currency = readCurrency();
    if (currency == 0xFFFF) return;

    double amount;
    if (!readDouble("amount (or 'q' to cancel): ", amount)) return;
    if (amount <= 0) {
        std::cout << "Amount must be positive.\n";
        return;
    }

    std::vector<uint8_t> body;
    proto::putString(body, name);
    proto::putI32(body, fromAccNo);
    proto::putPassword16(body, password);
    proto::putI32(body, toAccNo);
    proto::putU16(body, currency);
    proto::putDouble(body, amount);

    proto::Message reply;
    if (!call((uint16_t)proto::OpCode::TRANSFER, body, reply)) {
        std::cout << "TRANSFER failed: communication error\n";
        readLine("Press Enter to continue...");
        return;
    }

    if (reply.h.status != (uint16_t)proto::Status::OK) {
        std::cout << "TRANSFER failed, status=" << proto::statusToString(reply.h.status) << "\n";
        readLine("Press Enter to continue...");
        return;
    }

    size_t off = 0;
    double fromBal, toBal;
    if (proto::getDouble(reply.body, off, fromBal) && proto::getDouble(reply.body, off, toBal)) {
        std::cout << "Password & account verified. Hello, " << name << "!\n";
        std::cout << "TRANSFER OK. fromNewBal=" << fromBal << " toNewBal=" << toBal << "\n";
    }
    readLine("Press Enter to continue...");
}

void Client::handleMonitor() {
    clearScreen();
    std::cout << "=== MONITOR (enter 'q' to cancel) ===\n";

    int seconds;
    if (!readInt("monitor seconds (or 'q' to cancel): ", seconds)) return;
    if (seconds <= 0) {
        std::cout << "Seconds must be positive.\n";
        return;
    }

    std::vector<uint8_t> body;
    proto::putU16(body, (uint16_t)seconds);

    proto::Message reply;
    if (!call((uint16_t)proto::OpCode::MONITOR_REGISTER, body, reply)) {
        std::cout << "MONITOR failed: communication error\n";
        readLine("Press Enter to continue...");
        return;
    }

    if (reply.h.status != (uint16_t)proto::Status::OK) {
        std::cout << "MONITOR failed, status=" << proto::statusToString(reply.h.status) << "\n";
        readLine("Press Enter to continue...");
        return;
    }

    size_t off = 0;
    std::string msg;
    if (proto::getString(reply.body, off, msg)) {
        std::cout << "MONITOR OK: " << msg << "\n";
    }

    std::cout << "== Waiting callbacks for " << seconds << " seconds (client blocked) ==\n";

    // Set shorter timeout for polling
#ifdef _WIN32
    DWORD tv = 1000;
    setsockopt(sock_, SOL_SOCKET, SO_RCVTIMEO, (const char*)&tv, sizeof(tv));
#else
    struct timeval tv;
    tv.tv_sec = 1;
    tv.tv_usec = 0;
    setsockopt(sock_, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
#endif

    auto endTime = std::chrono::steady_clock::now() + std::chrono::seconds(seconds);

    while (std::chrono::steady_clock::now() < endTime) {
        std::vector<uint8_t> buf(2048);
        sockaddr_in fromAddr;
#ifdef _WIN32
        int fromLen = sizeof(fromAddr);
#else
        socklen_t fromLen = sizeof(fromAddr);
#endif

        int n = recvfrom(sock_, (char*)buf.data(), (int)buf.size(), 0,
                        (sockaddr*)&fromAddr, &fromLen);

        if (n < 0) {
            // Timeout, continue waiting
            continue;
        }

        buf.resize(n);
        proto::Message cb;
        if (!proto::decode(buf, cb)) continue;
        if (cb.h.msgType != (uint8_t)proto::MsgType::Callback) continue;
        if (cb.h.opCode != (uint16_t)proto::OpCode::CALLBACK_UPDATE) continue;

        size_t cbOff = 0;
        uint16_t updateType;
        int32_t accNo;
        uint16_t cur;
        double newBal;
        std::string info;

        if (proto::getU16(cb.body, cbOff, updateType) &&
            proto::getI32(cb.body, cbOff, accNo) &&
            proto::getU16(cb.body, cbOff, cur) &&
            proto::getDouble(cb.body, cbOff, newBal) &&
            proto::getString(cb.body, cbOff, info)) {
            
            std::cout << "[CALLBACK] type=" << proto::opCodeToString(updateType)
                      << " acc=" << accNo
                      << " cur=" << proto::currencyToString(cur)
                      << " newBal=" << newBal
                      << " info=" << info << "\n";
        }
    }

    std::cout << "== Monitor finished ==\n";
    readLine("Press Enter to continue...");
}
