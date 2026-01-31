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
  bool closeAccount(const std::string& name, int32_t accNo, const std::string& pw16, proto::Status& err);
  bool deposit(const std::string& name, int32_t accNo, const std::string& pw16, proto::Currency c, double amount, double& outBal, proto::Status& err);
  bool withdraw(const std::string& name, int32_t accNo, const std::string& pw16, proto::Currency c, double amount, double& outBal, proto::Status& err);
  bool transfer(const std::string& name, int32_t fromAccNo, const std::string& pw16, int32_t toAccNo, proto::Currency c, double amount,
                double& outFromBal, double& outToBal, proto::Status& err);
  bool queryBalance(const std::string& name, int32_t accNo, const std::string& pw16, proto::Currency& outCur, double& outBal, proto::Status& err);

  // for callback info
  const Account* getAccount(int32_t accNo) const;

private:
  int32_t nextAccNo_;
  std::unordered_map<int32_t, Account> accounts_;
};