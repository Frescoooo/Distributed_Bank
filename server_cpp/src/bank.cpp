#include "bank.hpp"

Bank::Bank() : nextAccNo_(10001) {}

bool Bank::openAccount(const std::string& name, const std::string& pw16, proto::Currency c, double initial,
                       int32_t& outAccNo, double& outBal, proto::Status& err) {
  if (pw16.size() == 0 || pw16.size() > 16) { err = proto::Status::ERR_PASSWORD_FORMAT; return false; }
  Account a;
  a.accountNo = nextAccNo_++;
  a.name = name;
  a.password = pw16;
  a.currency = c;
  a.balance = initial;
  accounts_[a.accountNo] = a;

  outAccNo = a.accountNo;
  outBal = a.balance;
  err = proto::Status::OK;
  return true;
}

bool Bank::queryBalance(const std::string& name, int32_t accNo, const std::string& pw16,
                        proto::Currency& outCur, double& outBal, proto::Status& err) {
  auto it = accounts_.find(accNo);
  if (it == accounts_.end() || it->second.closed) { err = proto::Status::ERR_NOT_FOUND; return false; }
  const Account& a = it->second;
  if (a.name != name || a.password != pw16) { err = proto::Status::ERR_AUTH; return false; }
  outCur = a.currency;
  outBal = a.balance;
  err = proto::Status::OK;
  return true;
}

const Account* Bank::getAccount(int32_t accNo) const {
  auto it = accounts_.find(accNo);
  if (it == accounts_.end()) return nullptr;
  return &it->second;
}