#pragma once
#include "protocol.hpp"
#include <cstdint>
#include <string>
#include <unordered_map>

struct Account {
  int32_t accountNo{};
  std::string name;
  std::string password; // stored as string (<=16 chars), validated via fixed 16 bytes in protocol
  proto::Currency currency{};
  double balance{};
  bool closed{false};
};

class Bank {
public:
  Bank();

  bool openAccount(const std::string& name, const std::string& pw16, proto::Currency c, double initial, int32_t& outAccNo, double& outBal, proto::Status& err);
  bool queryBalance(const std::string& name, int32_t accNo, const std::string& pw16, proto::Currency& outCur, double& outBal, proto::Status& err);

  // for callback info
  const Account* getAccount(int32_t accNo) const;

private:
  int32_t nextAccNo_;
  std::unordered_map<int32_t, Account> accounts_;
};