#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace proto {

static constexpr uint32_t MAGIC = 0x42414E4B; // 'BANK'
static constexpr uint8_t VERSION = 1;

enum class MsgType : uint8_t {
  Request  = 1,
  Reply    = 2,
  Callback = 3
};

enum class OpCode : uint16_t {
  OPEN = 1,
  QUERY_BALANCE = 6,
  MONITOR_REGISTER = 5,
  CALLBACK_UPDATE = 100
};

enum class Currency : uint16_t {
  CNY = 0,
  SGD = 1
};

enum class Status : uint16_t {
  OK = 0,
  ERR_BAD_REQUEST = 1,
  ERR_AUTH = 2,
  ERR_NOT_FOUND = 3,
  ERR_CURRENCY = 4,
  ERR_PASSWORD_FORMAT = 6
};

struct Header {
  uint32_t magic;
  uint8_t version;
  uint8_t msgType;
  uint16_t opCode;
  uint16_t flags;
  uint16_t status;
  uint64_t requestId;
  uint32_t bodyLen;
};

struct Message {
  Header h{};
  std::vector<uint8_t> body;
};

// bit0: 1 => at-most-once, 0 => at-least-once
static constexpr uint16_t FLAG_AT_MOST_ONCE = 0x0001;

// Encoding helpers (big-endian)
void putU16(std::vector<uint8_t>& b, uint16_t v);
void putU32(std::vector<uint8_t>& b, uint32_t v);
void putU64(std::vector<uint8_t>& b, uint64_t v);
void putI32(std::vector<uint8_t>& b, int32_t v);
void putDouble(std::vector<uint8_t>& b, double v);
void putString(std::vector<uint8_t>& b, const std::string& s); // u16 len + bytes
void putPassword16(std::vector<uint8_t>& b, const std::string& s); // fixed 16 bytes

bool getU16(const std::vector<uint8_t>& b, size_t& off, uint16_t& out);
bool getU32(const std::vector<uint8_t>& b, size_t& off, uint32_t& out);
bool getU64(const std::vector<uint8_t>& b, size_t& off, uint64_t& out);
bool getI32(const std::vector<uint8_t>& b, size_t& off, int32_t& out);
bool getDouble(const std::vector<uint8_t>& b, size_t& off, double& out);
bool getString(const std::vector<uint8_t>& b, size_t& off, std::string& out);
bool getPassword16(const std::vector<uint8_t>& b, size_t& off, std::string& out); // trims trailing '\0'

std::vector<uint8_t> encode(const Message& m);
bool decode(const std::vector<uint8_t>& raw, Message& out);

std::string currencyToString(uint16_t c);

} // namespace proto