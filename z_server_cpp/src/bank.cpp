#include "bank.hpp"

Bank::Bank() : nextAccNo_(10001) {}

static bool authMatch(const Account& a, const std::string& name, const std::string& pw16) {
  return a.name == name && a.password == pw16;
}

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

bool Bank::closeAccount(const std::string& name, int32_t accNo, const std::string& pw16, proto::Status& err) {
  auto it = accounts_.find(accNo);
  if (it == accounts_.end() || it->second.closed) { err = proto::Status::ERR_NOT_FOUND; return false; }
  Account& a = it->second;
  if (!authMatch(a, name, pw16)) { err = proto::Status::ERR_AUTH; return false; }
  a.closed = true;
  err = proto::Status::OK;
  return true;
}

bool Bank::deposit(const std::string& name, int32_t accNo, const std::string& pw16, proto::Currency c, double amount,
                   double& outBal, proto::Status& err) {
  auto it = accounts_.find(accNo);
  if (it == accounts_.end() || it->second.closed) { err = proto::Status::ERR_NOT_FOUND; return false; }
  Account& a = it->second;
  if (!authMatch(a, name, pw16)) { err = proto::Status::ERR_AUTH; return false; }
  if (a.currency != c) { err = proto::Status::ERR_CURRENCY; return false; }
  if (!(amount > 0.0)) { err = proto::Status::ERR_BAD_REQUEST; return false; }
  a.balance += amount;
  outBal = a.balance;
  err = proto::Status::OK;
  return true;
}

bool Bank::withdraw(const std::string& name, int32_t accNo, const std::string& pw16, proto::Currency c, double amount,
                    double& outBal, proto::Status& err) {
  auto it = accounts_.find(accNo);
  if (it == accounts_.end() || it->second.closed) { err = proto::Status::ERR_NOT_FOUND; return false; }
  Account& a = it->second;
  if (!authMatch(a, name, pw16)) { err = proto::Status::ERR_AUTH; return false; }
  if (a.currency != c) { err = proto::Status::ERR_CURRENCY; return false; }
  if (!(amount > 0.0)) { err = proto::Status::ERR_BAD_REQUEST; return false; }
  if (a.balance < amount) { err = proto::Status::ERR_INSUFFICIENT_FUNDS; return false; }
  a.balance -= amount;
  outBal = a.balance;
  err = proto::Status::OK;
  return true;
}

bool Bank::transfer(const std::string& name, int32_t fromAccNo, const std::string& pw16, int32_t toAccNo, proto::Currency c,
                    double amount, double& outFromBal, double& outToBal, proto::Status& err) {
  if (fromAccNo == toAccNo) { err = proto::Status::ERR_BAD_REQUEST; return false; }
  auto itFrom = accounts_.find(fromAccNo);
  auto itTo = accounts_.find(toAccNo);
  if (itFrom == accounts_.end() || itFrom->second.closed) { err = proto::Status::ERR_NOT_FOUND; return false; }
  if (itTo == accounts_.end() || itTo->second.closed) { err = proto::Status::ERR_NOT_FOUND; return false; }

  Account& from = itFrom->second;
  Account& to = itTo->second;
  if (!authMatch(from, name, pw16)) { err = proto::Status::ERR_AUTH; return false; }
  if (from.currency != c || to.currency != c) { err = proto::Status::ERR_CURRENCY; return false; }
  if (!(amount > 0.0)) { err = proto::Status::ERR_BAD_REQUEST; return false; }
  if (from.balance < amount) { err = proto::Status::ERR_INSUFFICIENT_FUNDS; return false; }

  from.balance -= amount;
  to.balance += amount;
  outFromBal = from.balance;
  outToBal = to.balance;
  err = proto::Status::OK;
  return true;
}

bool Bank::queryBalance(const std::string& name, int32_t accNo, const std::string& pw16,
                        proto::Currency& outCur, double& outBal, proto::Status& err) {
  auto it = accounts_.find(accNo);
  if (it == accounts_.end() || it->second.closed) { err = proto::Status::ERR_NOT_FOUND; return false; }
  const Account& a = it->second;
  if (!authMatch(a, name, pw16)) { err = proto::Status::ERR_AUTH; return false; }
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