#include "protocol.hpp"
#include <cstring>

namespace proto {

// ==================== Big-endian encoding helpers ====================

static inline void putBE16(uint8_t* p, uint16_t v) {
    p[0] = uint8_t((v >> 8) & 0xFF);
    p[1] = uint8_t(v & 0xFF);
}

static inline void putBE32(uint8_t* p, uint32_t v) {
    p[0] = uint8_t((v >> 24) & 0xFF);
    p[1] = uint8_t((v >> 16) & 0xFF);
    p[2] = uint8_t((v >> 8) & 0xFF);
    p[3] = uint8_t(v & 0xFF);
}

static inline void putBE64(uint8_t* p, uint64_t v) {
    for (int i = 0; i < 8; i++) {
        p[i] = uint8_t((v >> (56 - 8 * i)) & 0xFF);
    }
}

// ==================== Big-endian decoding helpers ====================

static inline bool getBE16(const std::vector<uint8_t>& b, size_t& off, uint16_t& out) {
    if (off + 2 > b.size()) return false;
    out = (uint16_t(b[off]) << 8) | uint16_t(b[off + 1]);
    off += 2;
    return true;
}

static inline bool getBE32(const std::vector<uint8_t>& b, size_t& off, uint32_t& out) {
    if (off + 4 > b.size()) return false;
    out = (uint32_t(b[off]) << 24) | (uint32_t(b[off + 1]) << 16) | 
          (uint32_t(b[off + 2]) << 8) | uint32_t(b[off + 3]);
    off += 4;
    return true;
}

static inline bool getBE64(const std::vector<uint8_t>& b, size_t& off, uint64_t& out) {
    if (off + 8 > b.size()) return false;
    out = 0;
    for (int i = 0; i < 8; i++) {
        out = (out << 8) | uint64_t(b[off + i]);
    }
    off += 8;
    return true;
}

// ==================== Public encoding functions ====================

void putU16(std::vector<uint8_t>& b, uint16_t v) {
    size_t pos = b.size();
    b.resize(pos + 2);
    putBE16(b.data() + pos, v);
}

void putU32(std::vector<uint8_t>& b, uint32_t v) {
    size_t pos = b.size();
    b.resize(pos + 4);
    putBE32(b.data() + pos, v);
}

void putU64(std::vector<uint8_t>& b, uint64_t v) {
    size_t pos = b.size();
    b.resize(pos + 8);
    putBE64(b.data() + pos, v);
}

void putI32(std::vector<uint8_t>& b, int32_t v) {
    putU32(b, uint32_t(v));
}

void putDouble(std::vector<uint8_t>& b, double v) {
    uint64_t bits = 0;
    static_assert(sizeof(double) == 8, "double must be 8 bytes");
    std::memcpy(&bits, &v, 8);
    putU64(b, bits);
}

void putString(std::vector<uint8_t>& b, const std::string& s) {
    if (s.size() > 65535) return;
    putU16(b, uint16_t(s.size()));
    b.insert(b.end(), s.begin(), s.end());
}

void putPassword16(std::vector<uint8_t>& b, const std::string& s) {
    std::string t = s;
    if (t.size() > 16) t.resize(16);
    size_t pos = b.size();
    b.resize(pos + 16, 0);
    std::memcpy(b.data() + pos, t.data(), t.size());
}

// ==================== Public decoding functions ====================

bool getU16(const std::vector<uint8_t>& b, size_t& off, uint16_t& out) {
    return getBE16(b, off, out);
}

bool getU32(const std::vector<uint8_t>& b, size_t& off, uint32_t& out) {
    return getBE32(b, off, out);
}

bool getU64(const std::vector<uint8_t>& b, size_t& off, uint64_t& out) {
    return getBE64(b, off, out);
}

bool getI32(const std::vector<uint8_t>& b, size_t& off, int32_t& out) {
    uint32_t u;
    if (!getU32(b, off, u)) return false;
    out = int32_t(u);
    return true;
}

bool getDouble(const std::vector<uint8_t>& b, size_t& off, double& out) {
    uint64_t bits;
    if (!getU64(b, off, bits)) return false;
    std::memcpy(&out, &bits, 8);
    return true;
}

bool getString(const std::vector<uint8_t>& b, size_t& off, std::string& out) {
    uint16_t len;
    if (!getU16(b, off, len)) return false;
    if (off + len > b.size()) return false;
    out.assign(reinterpret_cast<const char*>(b.data() + off), len);
    off += len;
    return true;
}

bool getPassword16(const std::vector<uint8_t>& b, size_t& off, std::string& out) {
    if (off + 16 > b.size()) return false;
    const uint8_t* p = b.data() + off;
    size_t n = 16;
    while (n > 0 && p[n - 1] == 0) n--;
    out.assign(reinterpret_cast<const char*>(p), n);
    off += 16;
    return true;
}

// ==================== Message encoding/decoding ====================

std::vector<uint8_t> encode(const Message& m) {
    std::vector<uint8_t> out;
    out.reserve(HEADER_SIZE + m.body.size());

    putU32(out, m.h.magic);
    out.push_back(m.h.version);
    out.push_back(m.h.msgType);
    putU16(out, m.h.opCode);
    putU16(out, m.h.flags);
    putU16(out, m.h.status);
    putU64(out, m.h.requestId);
    putU32(out, m.h.bodyLen);

    out.insert(out.end(), m.body.begin(), m.body.end());
    return out;
}

bool decode(const std::vector<uint8_t>& raw, Message& out) {
    size_t off = 0;
    if (!getU32(raw, off, out.h.magic)) return false;
    if (out.h.magic != MAGIC) return false;
    if (off + 2 > raw.size()) return false;
    out.h.version = raw[off++];
    out.h.msgType = raw[off++];
    if (!getU16(raw, off, out.h.opCode)) return false;
    if (!getU16(raw, off, out.h.flags)) return false;
    if (!getU16(raw, off, out.h.status)) return false;
    if (!getU64(raw, off, out.h.requestId)) return false;
    if (!getU32(raw, off, out.h.bodyLen)) return false;
    if (off + out.h.bodyLen > raw.size()) return false;
    out.body.assign(raw.begin() + off, raw.begin() + off + out.h.bodyLen);
    return true;
}

// ==================== Utility functions ====================

std::string currencyToString(uint16_t c) {
    if (c == uint16_t(Currency::CNY)) return "CNY";
    if (c == uint16_t(Currency::SGD)) return "SGD";
    return "UNKNOWN";
}

std::string statusToString(uint16_t s) {
    switch (s) {
        case uint16_t(Status::OK): return "OK";
        case uint16_t(Status::ERR_BAD_REQUEST): return "Request format error (BAD_REQUEST)";
        case uint16_t(Status::ERR_AUTH): return "Authentication failed: name/account/password mismatch (AUTH)";
        case uint16_t(Status::ERR_NOT_FOUND): return "Account not found or already closed (NOT_FOUND)";
        case uint16_t(Status::ERR_CURRENCY): return "Currency mismatch (CURRENCY)";
        case uint16_t(Status::ERR_INSUFFICIENT_FUNDS): return "ERR_INSUFFICIENT_FUNDS";
        case uint16_t(Status::ERR_PASSWORD_FORMAT): return "Password format error: must be 1..16 bytes (PASSWORD_FORMAT)";
        default: return "Unknown error status=" + std::to_string(s);
    }
}

std::string opCodeToString(uint16_t op) {
    switch (op) {
        case uint16_t(OpCode::OPEN): return "OPEN";
        case uint16_t(OpCode::CLOSE): return "CLOSE";
        case uint16_t(OpCode::DEPOSIT): return "DEPOSIT";
        case uint16_t(OpCode::WITHDRAW): return "WITHDRAW";
        case uint16_t(OpCode::MONITOR_REGISTER): return "MONITOR_REGISTER";
        case uint16_t(OpCode::QUERY_BALANCE): return "QUERY_BALANCE";
        case uint16_t(OpCode::TRANSFER): return "TRANSFER";
        case uint16_t(OpCode::CALLBACK_UPDATE): return "CALLBACK_UPDATE";
        default: return "UNKNOWN_OP";
    }
}

} // namespace proto
