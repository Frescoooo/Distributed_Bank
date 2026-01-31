import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UDP Server for the Distributed Banking System
 * 
 * Features:
 * - At-least-once and At-most-once invocation semantics
 * - Simulated message loss for testing
 * - Callback support for monitoring account updates
 * - Deduplication for at-most-once semantics
 */
public class Server {
    
    /**
     * Monitor entry for callback registration
     */
    static class MonitorEntry {
        InetAddress address;
        int port;
        long expireTime; // System.currentTimeMillis()

        MonitorEntry(InetAddress address, int port, long expireTime) {
            this.address = address;
            this.port = port;
            this.expireTime = expireTime;
        }

        String getKey() {
            return address.getHostAddress() + ":" + port;
        }
    }

    /**
     * Deduplication entry for at-most-once semantics
     */
    static class DedupEntry {
        byte[] replyBytes;
        long expireTime;

        DedupEntry(byte[] replyBytes, long expireTime) {
            this.replyBytes = replyBytes;
            this.expireTime = expireTime;
        }
    }

    private final DatagramSocket socket;
    private final Bank bank;
    private final double lossReq;  // Probability of dropping incoming requests
    private final double lossRep;  // Probability of dropping outgoing replies
    private final Random random;
    
    private final List<MonitorEntry> monitors;
    private final ConcurrentHashMap<String, DedupEntry> dedupCache;

    public Server(int port, double lossReq, double lossRep) throws SocketException {
        this.socket = new DatagramSocket(port);
        this.bank = new Bank();
        this.lossReq = lossReq;
        this.lossRep = lossRep;
        this.random = new Random();
        this.monitors = Collections.synchronizedList(new ArrayList<>());
        this.dedupCache = new ConcurrentHashMap<>();
        
        System.out.println("[server] UDP listening on port " + port 
            + " lossReq=" + lossReq + " lossRep=" + lossRep);
    }

    /**
     * Main server loop
     */
    public void run() {
        byte[] buffer = new byte[2048];
        
        while (true) {
            try {
                // Periodic cleanup of expired entries
                cleanupExpired();
                
                // Receive packet
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                
                String clientKey = packet.getAddress().getHostAddress() + ":" + packet.getPort();
                
                // Simulate request loss
                if (random.nextDouble() < lossReq) {
                    System.out.println("[server] DROP request from " + clientKey + " (simulated)");
                    continue;
                }
                
                // Decode message
                Protocol.Message req = Protocol.decode(packet.getData(), packet.getLength());
                if (req == null || req.version != Protocol.VERSION || req.msgType != Protocol.MSG_REQUEST) {
                    System.out.println("[server] Bad request from " + clientKey);
                    continue;
                }
                
                boolean atMostOnce = (req.flags & Protocol.FLAG_AT_MOST_ONCE) != 0;
                String dedupKey = clientKey + "#" + req.requestId;
                
                // Check for duplicate request (at-most-once)
                if (atMostOnce) {
                    DedupEntry cached = dedupCache.get(dedupKey);
                    if (cached != null) {
                        System.out.println("[server] DUP reqId=" + req.requestId + " from " + clientKey + " => replay cached reply");
                        if (random.nextDouble() < lossRep) {
                            System.out.println("[server] DROP reply (simulated)");
                        } else {
                            sendReply(cached.replyBytes, packet.getAddress(), packet.getPort());
                        }
                        continue;
                    }
                }
                
                System.out.println("[server] recv op=" + Protocol.opCodeToString(req.opCode) 
                    + " reqId=" + req.requestId + " from " + clientKey 
                    + " flags=" + req.flags + " (" + (atMostOnce ? "at-most-once" : "at-least-once") + ")");
                
                // Process request and build reply
                Protocol.Message rep = processRequest(req, packet.getAddress(), packet.getPort());
                byte[] repBytes = Protocol.encode(rep);
                
                // Cache reply for at-most-once
                if (atMostOnce) {
                    dedupCache.put(dedupKey, new DedupEntry(repBytes, System.currentTimeMillis() + 60000));
                }
                
                // Simulate reply loss
                if (random.nextDouble() < lossRep) {
                    System.out.println("[server] DROP reply to " + clientKey + " (simulated)");
                    continue;
                }
                
                sendReply(repBytes, packet.getAddress(), packet.getPort());
                
            } catch (Exception e) {
                System.err.println("[server] Error: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * Process a request and return the reply message
     */
    private Protocol.Message processRequest(Protocol.Message req, InetAddress clientAddr, int clientPort) {
        Protocol.Message rep = new Protocol.Message();
        rep.msgType = Protocol.MSG_REPLY;
        rep.opCode = req.opCode;
        rep.flags = req.flags;
        rep.status = Protocol.STATUS_OK;
        rep.requestId = req.requestId;

        try {
            ByteBuffer bb = ByteBuffer.wrap(req.body).order(ByteOrder.BIG_ENDIAN);
            ByteBuffer repBody = ByteBuffer.allocate(1024).order(ByteOrder.BIG_ENDIAN);

            switch (req.opCode) {
                case Protocol.OP_OPEN:
                    handleOpen(bb, repBody, rep);
                    break;
                case Protocol.OP_CLOSE:
                    handleClose(bb, repBody, rep);
                    break;
                case Protocol.OP_DEPOSIT:
                    handleDeposit(bb, repBody, rep);
                    break;
                case Protocol.OP_WITHDRAW:
                    handleWithdraw(bb, repBody, rep);
                    break;
                case Protocol.OP_QUERY_BALANCE:
                    handleQueryBalance(bb, repBody, rep);
                    break;
                case Protocol.OP_TRANSFER:
                    handleTransfer(bb, repBody, rep);
                    break;
                case Protocol.OP_MONITOR_REGISTER:
                    handleMonitorRegister(bb, repBody, rep, clientAddr, clientPort);
                    break;
                default:
                    rep.status = Protocol.STATUS_ERR_BAD_REQUEST;
                    break;
            }

            // Copy body to reply
            rep.body = Arrays.copyOf(repBody.array(), repBody.position());
            
        } catch (Exception e) {
            System.err.println("[server] Error processing request: " + e.getMessage());
            rep.status = Protocol.STATUS_ERR_BAD_REQUEST;
            rep.body = new byte[0];
        }

        return rep;
    }

    // ==================== Operation handlers ====================

    private void handleOpen(ByteBuffer bb, ByteBuffer repBody, Protocol.Message rep) {
        String name = Protocol.getString(bb);
        String password = Protocol.getPassword16(bb);
        short currency = bb.getShort();
        double initialBalance = Double.longBitsToDouble(bb.getLong());

        Bank.Result[] result = new Bank.Result[1];
        Object[] res = bank.openAccount(name, password, currency, initialBalance, result);

        if (res == null) {
            rep.status = result[0].status;
            return;
        }

        int accountNo = (Integer) res[0];
        double balance = (Double) res[1];

        repBody.putInt(accountNo);
        repBody.putLong(Double.doubleToLongBits(balance));

        System.out.println("[server] OPEN: accountNo=" + accountNo + " name=" + name 
            + " currency=" + Protocol.currencyToString(currency) + " balance=" + balance);

        // Send callback
        sendUpdateCallback(Protocol.OP_OPEN, accountNo, currency, balance, "OPEN by " + name);
    }

    private void handleClose(ByteBuffer bb, ByteBuffer repBody, Protocol.Message rep) {
        String name = Protocol.getString(bb);
        int accountNo = bb.getInt();
        String password = Protocol.getPassword16(bb);

        // Get account info before closing (for callback)
        Bank.Account account = bank.getAccount(accountNo);
        short currency = account != null ? account.currency : 0;
        double balance = account != null ? account.balance : 0;

        Bank.Result result = bank.closeAccount(name, accountNo, password);

        if (!result.success) {
            rep.status = result.status;
            return;
        }

        Protocol.putString(repBody, "account closed");

        System.out.println("[server] CLOSE: accountNo=" + accountNo + " name=" + name);

        // Send callback
        sendUpdateCallback(Protocol.OP_CLOSE, accountNo, currency, balance, "CLOSE by " + name);
    }

    private void handleDeposit(ByteBuffer bb, ByteBuffer repBody, Protocol.Message rep) {
        String name = Protocol.getString(bb);
        int accountNo = bb.getInt();
        String password = Protocol.getPassword16(bb);
        short currency = bb.getShort();
        double amount = Double.longBitsToDouble(bb.getLong());

        Bank.Result[] result = new Bank.Result[1];
        Double newBalance = bank.deposit(name, accountNo, password, currency, amount, result);

        if (newBalance == null) {
            rep.status = result[0].status;
            return;
        }

        repBody.putLong(Double.doubleToLongBits(newBalance));

        System.out.println("[server] DEPOSIT: accountNo=" + accountNo + " amount=" + amount 
            + " newBalance=" + newBalance);

        // Send callback
        sendUpdateCallback(Protocol.OP_DEPOSIT, accountNo, currency, newBalance, 
            "DEPOSIT " + amount + " by " + name);
    }

    private void handleWithdraw(ByteBuffer bb, ByteBuffer repBody, Protocol.Message rep) {
        String name = Protocol.getString(bb);
        int accountNo = bb.getInt();
        String password = Protocol.getPassword16(bb);
        short currency = bb.getShort();
        double amount = Double.longBitsToDouble(bb.getLong());

        Bank.Result[] result = new Bank.Result[1];
        Double newBalance = bank.withdraw(name, accountNo, password, currency, amount, result);

        if (newBalance == null) {
            rep.status = result[0].status;
            return;
        }

        repBody.putLong(Double.doubleToLongBits(newBalance));

        System.out.println("[server] WITHDRAW: accountNo=" + accountNo + " amount=" + amount 
            + " newBalance=" + newBalance);

        // Send callback
        sendUpdateCallback(Protocol.OP_WITHDRAW, accountNo, currency, newBalance, 
            "WITHDRAW " + amount + " by " + name);
    }

    private void handleQueryBalance(ByteBuffer bb, ByteBuffer repBody, Protocol.Message rep) {
        String name = Protocol.getString(bb);
        int accountNo = bb.getInt();
        String password = Protocol.getPassword16(bb);

        Bank.Result[] result = new Bank.Result[1];
        Object[] res = bank.queryBalance(name, accountNo, password, result);

        if (res == null) {
            rep.status = result[0].status;
            return;
        }

        short currency = (Short) res[0];
        double balance = (Double) res[1];

        repBody.putShort(currency);
        repBody.putLong(Double.doubleToLongBits(balance));

        System.out.println("[server] QUERY_BALANCE: accountNo=" + accountNo 
            + " currency=" + Protocol.currencyToString(currency) + " balance=" + balance);
    }

    private void handleTransfer(ByteBuffer bb, ByteBuffer repBody, Protocol.Message rep) {
        String name = Protocol.getString(bb);
        int fromAccountNo = bb.getInt();
        String password = Protocol.getPassword16(bb);
        int toAccountNo = bb.getInt();
        short currency = bb.getShort();
        double amount = Double.longBitsToDouble(bb.getLong());

        Bank.Result[] result = new Bank.Result[1];
        Double[] balances = bank.transfer(name, fromAccountNo, password, toAccountNo, currency, amount, result);

        if (balances == null) {
            rep.status = result[0].status;
            return;
        }

        repBody.putLong(Double.doubleToLongBits(balances[0]));
        repBody.putLong(Double.doubleToLongBits(balances[1]));

        System.out.println("[server] TRANSFER: from=" + fromAccountNo + " to=" + toAccountNo 
            + " amount=" + amount + " fromNewBal=" + balances[0] + " toNewBal=" + balances[1]);

        // Send callbacks for both accounts
        sendUpdateCallback(Protocol.OP_TRANSFER, fromAccountNo, currency, balances[0], 
            "TRANSFER out " + amount + " to " + toAccountNo + " by " + name);
        sendUpdateCallback(Protocol.OP_TRANSFER, toAccountNo, currency, balances[1], 
            "TRANSFER in " + amount + " from " + fromAccountNo);
    }

    private void handleMonitorRegister(ByteBuffer bb, ByteBuffer repBody, Protocol.Message rep,
                                       InetAddress clientAddr, int clientPort) {
        short seconds = bb.getShort();

        if (seconds <= 0) {
            rep.status = Protocol.STATUS_ERR_BAD_REQUEST;
            return;
        }

        MonitorEntry entry = new MonitorEntry(clientAddr, clientPort, 
            System.currentTimeMillis() + seconds * 1000L);
        monitors.add(entry);

        Protocol.putString(repBody, "monitor registered for " + seconds + "s");

        System.out.println("[server] MONITOR_REGISTER: " + entry.getKey() + " for " + seconds + "s");
    }

    // ==================== Callback handling ====================

    /**
     * Send update callback to all registered monitors
     */
    private void sendUpdateCallback(short updateType, int accountNo, short currency, 
                                    double balance, String info) {
        synchronized (monitors) {
            for (MonitorEntry monitor : monitors) {
                try {
                    Protocol.Message cb = new Protocol.Message();
                    cb.msgType = Protocol.MSG_CALLBACK;
                    cb.opCode = Protocol.OP_CALLBACK_UPDATE;
                    cb.flags = 0;
                    cb.status = Protocol.STATUS_OK;
                    cb.requestId = 0;

                    ByteBuffer body = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN);
                    body.putShort(updateType);
                    body.putInt(accountNo);
                    body.putShort(currency);
                    body.putLong(Double.doubleToLongBits(balance));
                    Protocol.putString(body, info);

                    cb.body = Arrays.copyOf(body.array(), body.position());

                    byte[] cbBytes = Protocol.encode(cb);
                    DatagramPacket packet = new DatagramPacket(cbBytes, cbBytes.length, 
                        monitor.address, monitor.port);
                    socket.send(packet);

                    System.out.println("[server] CALLBACK sent to " + monitor.getKey() 
                        + ": " + Protocol.opCodeToString(updateType) + " acc=" + accountNo);
                } catch (Exception e) {
                    System.err.println("[server] Failed to send callback: " + e.getMessage());
                }
            }
        }
    }

    // ==================== Utility methods ====================

    private void sendReply(byte[] data, InetAddress address, int port) throws Exception {
        DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
        socket.send(packet);
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        
        // Cleanup monitors
        synchronized (monitors) {
            monitors.removeIf(m -> m.expireTime <= now);
        }
        
        // Cleanup dedup cache
        dedupCache.entrySet().removeIf(e -> e.getValue().expireTime <= now);
    }

    // ==================== Main entry point ====================

    public static void main(String[] args) {
        int port = 9000;
        double lossReq = 0.0;
        double lossRep = 0.0;

        // Parse command line arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port":
                    port = Integer.parseInt(args[++i]);
                    break;
                case "--lossReq":
                    lossReq = Double.parseDouble(args[++i]);
                    break;
                case "--lossRep":
                    lossRep = Double.parseDouble(args[++i]);
                    break;
                default:
                    System.out.println("Usage: java Server --port 9000 --lossReq 0.0 --lossRep 0.0");
                    System.out.println("  --port: Server port (default: 9000)");
                    System.out.println("  --lossReq: Request loss probability 0.0-1.0 (default: 0.0)");
                    System.out.println("  --lossRep: Reply loss probability 0.0-1.0 (default: 0.0)");
                    return;
            }
        }

        try {
            Server server = new Server(port, lossReq, lossRep);
            server.run();
        } catch (Exception e) {
            System.err.println("Failed to start server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
