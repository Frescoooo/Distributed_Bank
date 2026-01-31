import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bank class manages all bank accounts and provides operations on them.
 * Thread-safe implementation using ConcurrentHashMap and atomic operations.
 */
public class Bank {
    
    /**
     * Account data structure
     */
    public static class Account {
        public final int accountNo;
        public final String name;
        public final String password;  // stored as string (<=16 chars)
        public final short currency;
        public volatile double balance;
        public volatile boolean closed;

        public Account(int accountNo, String name, String password, short currency, double balance) {
            this.accountNo = accountNo;
            this.name = name;
            this.password = password;
            this.currency = currency;
            this.balance = balance;
            this.closed = false;
        }
    }

    /**
     * Result class for operations that return values
     */
    public static class Result {
        public final boolean success;
        public final short status;
        public final String message;

        public Result(boolean success, short status, String message) {
            this.success = success;
            this.status = status;
            this.message = message;
        }

        public static Result ok() {
            return new Result(true, Protocol.STATUS_OK, "OK");
        }

        public static Result ok(String message) {
            return new Result(true, Protocol.STATUS_OK, message);
        }

        public static Result error(short status, String message) {
            return new Result(false, status, message);
        }
    }

    private final ConcurrentHashMap<Integer, Account> accounts;
    private final AtomicInteger nextAccountNo;

    public Bank() {
        this.accounts = new ConcurrentHashMap<>();
        this.nextAccountNo = new AtomicInteger(10001);
    }

    /**
     * Open a new bank account
     * @return [accountNo, balance] on success, or null on failure
     */
    public synchronized Object[] openAccount(String name, String password, short currency, double initialBalance, Result[] outResult) {
        // Validate password
        if (password == null || password.isEmpty() || password.length() > 16) {
            outResult[0] = Result.error(Protocol.STATUS_ERR_PASSWORD_FORMAT, "Password must be 1-16 characters");
            return null;
        }

        // Validate initial balance
        if (initialBalance < 0) {
            outResult[0] = Result.error(Protocol.STATUS_ERR_BAD_REQUEST, "Initial balance cannot be negative");
            return null;
        }

        // Create new account
        int accountNo = nextAccountNo.getAndIncrement();
        Account account = new Account(accountNo, name, password, currency, initialBalance);
        accounts.put(accountNo, account);

        outResult[0] = Result.ok("Account created successfully");
        return new Object[]{accountNo, initialBalance};
    }

    /**
     * Close an existing account
     */
    public synchronized Result closeAccount(String name, int accountNo, String password) {
        Account account = accounts.get(accountNo);
        
        // Check if account exists and is not closed
        if (account == null || account.closed) {
            return Result.error(Protocol.STATUS_ERR_NOT_FOUND, "Account not found or already closed");
        }

        // Verify name and password
        if (!authenticate(account, name, password)) {
            return Result.error(Protocol.STATUS_ERR_AUTH, "Authentication failed");
        }

        // Mark as closed
        account.closed = true;
        return Result.ok("Account closed successfully");
    }

    /**
     * Deposit money into an account
     * @return new balance on success, or null on failure
     */
    public synchronized Double deposit(String name, int accountNo, String password, short currency, double amount, Result[] outResult) {
        Account account = accounts.get(accountNo);
        
        if (account == null || account.closed) {
            outResult[0] = Result.error(Protocol.STATUS_ERR_NOT_FOUND, "Account not found or closed");
            return null;
        }

        if (!authenticate(account, name, password)) {
            outResult[0] = Result.error(Protocol.STATUS_ERR_AUTH, "Authentication failed");
            return null;
        }

        if (account.currency != currency) {
            outResult[0] = Result.error(Protocol.STATUS_ERR_CURRENCY, "Currency mismatch");
            return null;
        }

        if (amount <= 0) {
            outResult[0] = Result.error(Protocol.STATUS_ERR_BAD_REQUEST, "Amount must be positive");
            return null;
        }

        account.balance += amount;
        outResult[0] = Result.ok();
        return account.balance;
    }

    /**
     * Withdraw money from an account
     * @return new balance on success, or null on failure
     */
    public synchronized Double withdraw(String name, int accountNo, String password, short currency, double amount, Result[] outResult) {
        Account account = accounts.get(accountNo);
        
        if (account == null || account.closed) {
            outResult[0] = Result.error(Protocol.STATUS_ERR_NOT_FOUND, "Account not found or closed");
            return null;
        }

        if (!authenticate(account, name, password)) {
            outResult[0] = Result.error(Protocol.STATUS_ERR_AUTH, "Authentication failed");
            return null;
        }

        if (account.currency != currency) {
            outResult[0] = Result.error(Protocol.STATUS_ERR_CURRENCY, "Currency mismatch");
            return null;
        }

        if (amount <= 0) {
            outResult[0] = Result.error(Protocol.STATUS_ERR_BAD_REQUEST, "Amount must be positive");
            return null;
        }

        if (account.balance < amount) {
            outResult[0] = Result.error(Protocol.STATUS_ERR_INSUFFICIENT_FUNDS, "Insufficient funds");
            return null;
        }

        account.balance -= amount;
        outResult[0] = Result.ok();
        return account.balance;
    }

    /**
     * Query account balance
     * @return [currency, balance] on success, or null on failure
     */
    public synchronized Object[] queryBalance(String name, int accountNo, String password, Result[] outResult) {
        Account account = accounts.get(accountNo);
        
        if (account == null || account.closed) {
            outResult[0] = Result.error(Protocol.STATUS_ERR_NOT_FOUND, "Account not found or closed");
            return null;
        }

        if (!authenticate(account, name, password)) {
            outResult[0] = Result.error(Protocol.STATUS_ERR_AUTH, "Authentication failed");
            return null;
        }

        outResult[0] = Result.ok();
        return new Object[]{account.currency, account.balance};
    }

    /**
     * Transfer money between accounts (non-idempotent operation)
     * @return [fromNewBalance, toNewBalance] on success, or null on failure
     */
    public synchronized Double[] transfer(String name, int fromAccountNo, String password, 
                                          int toAccountNo, short currency, double amount, Result[] outResult) {
        if (fromAccountNo == toAccountNo) {
            outResult[0] = Result.error(Protocol.STATUS_ERR_BAD_REQUEST, "Cannot transfer to same account");
            return null;
        }

        Account fromAccount = accounts.get(fromAccountNo);
        Account toAccount = accounts.get(toAccountNo);
        
        if (fromAccount == null || fromAccount.closed) {
            outResult[0] = Result.error(Protocol.STATUS_ERR_NOT_FOUND, "Source account not found or closed");
            return null;
        }

        if (toAccount == null || toAccount.closed) {
            outResult[0] = Result.error(Protocol.STATUS_ERR_NOT_FOUND, "Destination account not found or closed");
            return null;
        }

        if (!authenticate(fromAccount, name, password)) {
            outResult[0] = Result.error(Protocol.STATUS_ERR_AUTH, "Authentication failed");
            return null;
        }

        if (fromAccount.currency != currency || toAccount.currency != currency) {
            outResult[0] = Result.error(Protocol.STATUS_ERR_CURRENCY, "Currency mismatch");
            return null;
        }

        if (amount <= 0) {
            outResult[0] = Result.error(Protocol.STATUS_ERR_BAD_REQUEST, "Amount must be positive");
            return null;
        }

        if (fromAccount.balance < amount) {
            outResult[0] = Result.error(Protocol.STATUS_ERR_INSUFFICIENT_FUNDS, "Insufficient funds");
            return null;
        }

        // Perform transfer atomically
        fromAccount.balance -= amount;
        toAccount.balance += amount;

        outResult[0] = Result.ok();
        return new Double[]{fromAccount.balance, toAccount.balance};
    }

    /**
     * Get account for callback information
     */
    public Account getAccount(int accountNo) {
        return accounts.get(accountNo);
    }

    /**
     * Authenticate user against account
     */
    private boolean authenticate(Account account, String name, String password) {
        return account.name.equals(name) && account.password.equals(password);
    }
}
