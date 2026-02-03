import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import java.util.Scanner;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Cli {
    static class CommunicationException extends Exception {
        public CommunicationException(String message) { super(message); }
    }

    private final DatagramSocket sock;
    private final InetAddress serverIp;
    private final int serverPort;
    private final boolean atMostOnce;
    private final int timeoutMs;
    private final int retry;
    private final Random rnd = new Random();
    private static final DateTimeFormatter RECEIPT_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public Cli(DatagramSocket sock, InetAddress serverIp, int serverPort, boolean atMostOnce, int timeoutMs, int retry) {
        this.sock = sock;
        this.serverIp = serverIp;
        this.serverPort = serverPort;
        this.atMostOnce = atMostOnce;
        this.timeoutMs = timeoutMs;
        this.retry = retry;
    }

    private static void clearScreen() {
        // ANSI escape codes for clearing screen might not work on all terminals,
        // so we'll just print a lot of newlines to simulate it.
        for (int i = 0; i < 50; ++i) {
            System.out.println();
        }
    }

    /**
     * Print a formatted operation receipt for the user.
     * Purely for display; does not affect any logic or state.
     */
    private static void printReceipt(String opName, String status, String... detailLines) {
        System.out.println("\n------------ Operation Receipt ------------");
        System.out.println(" Time   : " + LocalDateTime.now().format(RECEIPT_TIME_FMT));
        System.out.println(" Op     : " + opName);
        System.out.println(" Status : " + status);
        if (detailLines != null && detailLines.length > 0) {
            System.out.println(" Details:");
            for (String line : detailLines) {
                System.out.println("   - " + line);
            }
        }
        System.out.println("-------------------------------------------");
    }

    private Protocol.Message call(short opCode, byte[] body) throws CommunicationException {
        long reqId = Math.abs(rnd.nextLong());
        Protocol.Message req = new Protocol.Message();
        req.magic = Protocol.MAGIC;
        req.version = Protocol.VERSION;
        req.msgType = Protocol.MSG_REQUEST;
        req.opCode = opCode;
        req.flags = (short) (atMostOnce ? Protocol.FLAG_AT_MOST_ONCE : 0);
        req.status = 0;
        req.requestId = reqId;
        req.body = body;

        byte[] out = Protocol.encode(req);
        System.out.println("[debug] sending request op=" + Protocol.opCodeToString(opCode)
                + " bodyLen=" + (body != null ? body.length : 0)
                + " totalLen=" + out.length
                + " reqId=" + reqId);
        DatagramPacket p = new DatagramPacket(out, out.length, serverIp, serverPort);

        try {
            sock.setSoTimeout(timeoutMs);
        } catch (SocketException e) {
            throw new CommunicationException("Failed to set socket timeout: " + e.getMessage());
        }

        for (int attempt = 1; attempt <= retry; attempt++) {
            try {
                sock.send(p);
                byte[] buf = new byte[2048];
                DatagramPacket rp = new DatagramPacket(buf, buf.length);
                sock.receive(rp);
                Protocol.Message rep = Protocol.decode(rp.getData(), rp.getLength());
                if (rep == null) {
                    System.out.println("[debug] decode() returned null (len=" + rp.getLength() + "), ignore and retry");
                    continue;
                }
                if (rep.msgType != Protocol.MSG_REPLY) {
                    System.out.println("[debug] ignore non-reply msgType=" + rep.msgType + " opCode=" + rep.opCode);
                    continue;
                }
                if (rep.requestId != reqId) {
                    System.out.println("[debug] ignore reply for other reqId=" + rep.requestId + " (expect " + reqId + ")");
                    continue; // ignore unrelated (e.g. callbacks or old replies)
                }
                System.out.println("[debug] got reply: op=" + Protocol.opCodeToString(rep.opCode)
                        + " status=" + Protocol.statusToString(rep.status)
                        + " reqId=" + rep.requestId);
                return rep;
            } catch (SocketTimeoutException e) {
                System.out.println("[debug] timeout waiting for reply, retry " + attempt + "/" + retry);
            } catch (SocketException e) { // Catch SocketException specifically
                System.out.println("[debug] socket error: " + e.getMessage());
                // For socket errors, it's often non-recoverable, so we'll just break the retry loop
                // and let the CommunicationException be thrown.
                break; // Exit retry loop
            } catch (Exception e) {
                System.out.println("[debug] unexpected error during send/receive: " + e.getMessage());
                throw new CommunicationException("Unexpected error during communication: " + e.getMessage());
            }
        }
        throw new CommunicationException("request failed after retries");
    }

    private char[] readPasswordMasked(Scanner sc, String prompt) {
        if (System.console() != null) {
            return System.console().readPassword(prompt);
        } else {
            System.out.print(prompt);
            return sc.nextLine().toCharArray();
        }
    }

    // Returns null if user wants to cancel
    private char[] readPasswordWithCancel(Scanner sc, String prompt) {
        System.out.print(prompt + " (or 'q' to cancel): ");
        String input = sc.nextLine();
        if (input.equalsIgnoreCase("q")) {
            return null;
        }
        return input.toCharArray();
    }

    public void run() throws Exception {
        // Use try-with-resources to avoid "Scanner not closed" warning
        try (Scanner sc = new Scanner(System.in)) {
            while (true) {
                System.out.println();
                System.out.println("========================================");
                System.out.println(" Distributed Bank Client");
                System.out.println(" Server: " + serverIp.getHostAddress() + ":" + serverPort
                        + "  |  Semantics: " + (atMostOnce ? "AT-MOST-ONCE" : "AT-LEAST-ONCE"));
                System.out.println(" Timeout: " + timeoutMs + " ms  |  Retries: " + retry);
                System.out.println("========================================");
                System.out.println("1) OPEN account");
                System.out.println("2) CLOSE account");
                System.out.println("3) DEPOSIT (non-idempotent)");
                System.out.println("4) WITHDRAW (non-idempotent)");
                System.out.println("5) QUERY balance (idempotent, extra op)");
                System.out.println("6) TRANSFER (non-idempotent, extra op)");
                System.out.println("7) MONITOR register (callback)");
                System.out.println("0) EXIT");
                System.out.print("Select an option (0-7): ");
                String choice = sc.nextLine().trim();

                switch (choice) {
                    case "0":
                        System.out.println("\nGoodbye. Client exiting.");
                        return;

                    case "1": { // OPEN
                        clearScreen();
                        System.out.println("=== OPEN Account (enter 'q' at any prompt to cancel) ===");
                        boolean success = false;
                        do {
                            String name = "";
                            char[] pw = null;
                            char[] pwConfirm = null;
                            String curStr = "";
                            short cur = 0;
                            double init = 0.0;
                            try {
                                System.out.print("name (or 'q' to cancel): ");
                                name = sc.nextLine().trim();
                                if (name.equalsIgnoreCase("q")) throw new IllegalArgumentException("Operation cancelled by user.");
                                if (name.isEmpty()) throw new IllegalArgumentException("Name cannot be empty.");

                                while (true) {
                                    pw = readPasswordWithCancel(sc, "password (1..16 bytes)");
                                    if (pw == null) {
                                        throw new IllegalArgumentException("Operation cancelled by user.");
                                    }
                                    pwConfirm = readPasswordWithCancel(sc, "confirm password");
                                    if (pwConfirm == null) {
                                        Arrays.fill(pw, ' ');
                                        throw new IllegalArgumentException("Operation cancelled by user.");
                                    }
                                    if (!Arrays.equals(pw, pwConfirm)) {
                                        System.out.println("Passwords do not match. Please try again (or 'q' to cancel).");
                                        Arrays.fill(pw, ' ');
                                        Arrays.fill(pwConfirm, ' ');
                                        continue;
                                    }
                                    if (pw.length == 0 || pw.length > 16) {
                                        System.out.println("Password length must be 1..16 bytes. Please try again.");
                                        Arrays.fill(pw, ' ');
                                        Arrays.fill(pwConfirm, ' ');
                                        continue;
                                    }
                                    break;
                                }

                                System.out.print("currency (CNY/SGD, or 'q' to cancel): ");
                                curStr = sc.nextLine().trim().toUpperCase();
                                if (curStr.equals("Q")) throw new IllegalArgumentException("Operation cancelled by user.");
                                cur = (short) (curStr.equals("SGD") ? Protocol.CUR_SGD : Protocol.CUR_CNY);
                                if (!curStr.equals("CNY") && !curStr.equals("SGD")) {
                                    throw new IllegalArgumentException("Invalid currency type. Please use CNY or SGD.");
                                }
                                System.out.print("initial balance (or 'q' to cancel): ");
                                String initStr = sc.nextLine().trim();
                                if (initStr.equalsIgnoreCase("q")) throw new IllegalArgumentException("Operation cancelled by user.");
                                try {
                                    init = Double.parseDouble(initStr);
                                } catch (NumberFormatException e) {
                                    throw new IllegalArgumentException("Invalid input: initial balance must be a number.");
                                }
                                if (init < 0) {
                                    throw new IllegalArgumentException("Initial balance cannot be negative.");
                                }

                                byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                ByteBuffer bb = ByteBuffer.allocate(2 + nameBytes.length + 16 + 2 + 8).order(ByteOrder.BIG_ENDIAN);
                                Protocol.putString(bb, name);
                                Protocol.putPassword16(bb, pw); // Pass char[] to putPassword16
                                bb.putShort(cur);
                                bb.putDouble(init);
                                byte[] bodyBytes = Arrays.copyOf(bb.array(), bb.position());

                                Protocol.Message rep = call(Protocol.OP_OPEN, bodyBytes);
                                if (rep.status != Protocol.STATUS_OK) {
                                    System.out.println("\n[ERROR] OPEN failed: " + Protocol.statusToString(rep.status));
                                    throw new IllegalArgumentException("Operation failed on server: " + Protocol.statusToString(rep.status));
                                } else {
                                    ByteBuffer rb = ByteBuffer.wrap(rep.body).order(ByteOrder.BIG_ENDIAN);
                                    int accNo = rb.getInt();
                                    double bal = Double.longBitsToDouble(rb.getLong());
                                    System.out.println("\n[OK] Account opened successfully.");
                                    printReceipt(
                                            "OPEN",
                                            "SUCCESS",
                                            "AccountNo: " + accNo,
                                            "Name: " + name,
                                            "Currency: " + curStr,
                                            "Initial balance: " + bal
                                    );
                                }
                                success = true;
                            } catch (NumberFormatException e) {
                                System.out.println("Invalid input: initial balance must be a number. Returned to menu.");
                                break;
                            } catch (CommunicationException e) {
                                System.out.println("\n[NETWORK ERROR] " + e.getMessage());
                                System.out.print("Retry this operation? (y/n): ");
                                String retryChoice = sc.nextLine().trim().toLowerCase();
                                if (!retryChoice.equals("y")) {
                                    break; // Exit do-while, return to main menu
                                }
                            } catch (RuntimeException e) {
                                System.out.println("\n[NETWORK ERROR] No reply from server (UDP packet loss / wrong port / server not running / firewall).");
                                System.out.println("Details: " + e.getMessage());
                                break;
                            } catch (IllegalArgumentException e) {
                                System.out.println("\n[INPUT ERROR] " + e.getMessage());
                                System.out.print("Try again? (y/n): ");
                                String retryChoice = sc.nextLine().trim().toLowerCase();
                                if (!retryChoice.equals("y")) {
                                    break; // Exit do-while, return to main menu
                                }
                            } catch (Exception e) {
                                System.out.println("\n[UNEXPECTED ERROR] OPEN failed: " + e.getMessage());
                                break;
                            } finally {
                                if (pw != null) Arrays.fill(pw, ' ');
                                if (pwConfirm != null) Arrays.fill(pwConfirm, ' ');
                            }
                        } while (!success);
                        System.out.print("\nPress Enter to return to menu...");
                        sc.nextLine();
                        break;
                    }

                    case "2": { // CLOSE
                        clearScreen();
                        System.out.println("=== CLOSE Account (enter 'q' at any prompt to cancel) ===");
                        boolean success = false;
                        do {
                            String name = "";
                            int acc = 0;
                            char[] pw = null;
                            try {
                                System.out.print("name (or 'q' to cancel): ");
                                name = sc.nextLine().trim();
                                if (name.equalsIgnoreCase("q")) throw new IllegalArgumentException("Operation cancelled by user.");
                                if (name.isEmpty()) throw new IllegalArgumentException("Name cannot be empty.");
                                System.out.print("accountNo (or 'q' to cancel): ");
                                String accStr = sc.nextLine().trim();
                                if (accStr.equalsIgnoreCase("q")) throw new IllegalArgumentException("Operation cancelled by user.");
                                acc = Integer.parseInt(accStr);
                                if (acc <= 0) throw new IllegalArgumentException("Account number must be positive.");
                                pw = readPasswordWithCancel(sc, "password (1..16 bytes)");
                                if (pw == null) {
                                    throw new IllegalArgumentException("Operation cancelled by user.");
                                }
                                if (pw.length == 0 || pw.length > 16) {
                                    throw new IllegalArgumentException("Password length must be 1..16 bytes.");
                                }

                                byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                ByteBuffer bb = ByteBuffer.allocate(2 + nameBytes.length + 4 + 16).order(ByteOrder.BIG_ENDIAN);
                                Protocol.putString(bb, name);
                                bb.putInt(acc);
                                Protocol.putPassword16(bb, pw);
                                byte[] bodyBytes = Arrays.copyOf(bb.array(), bb.position());

                                Protocol.Message rep = call(Protocol.OP_CLOSE, bodyBytes);
                                if (rep.status != Protocol.STATUS_OK) {
                                    System.out.println("\n[ERROR] CLOSE failed: " + Protocol.statusToString(rep.status));
                                    throw new IllegalArgumentException("Operation failed on server: " + Protocol.statusToString(rep.status));
                                } else {
                                    ByteBuffer rb = ByteBuffer.wrap(rep.body).order(ByteOrder.BIG_ENDIAN);
                                    String msg = Protocol.getString(rb);
                                    System.out.println("\n[OK] Account closed successfully.");
                                    printReceipt(
                                            "CLOSE",
                                            "SUCCESS",
                                            "AccountNo: " + acc,
                                            "Name: " + name,
                                            "Message: " + msg
                                    );
                                }
                                success = true;
                            } catch (CommunicationException e) {
                                System.out.println("\n[NETWORK ERROR] " + e.getMessage());
                                System.out.print("Retry this operation? (y/n): ");
                                String retryChoice = sc.nextLine().trim().toLowerCase();
                                if (!retryChoice.equals("y")) {
                                    break; // Exit do-while, return to main menu
                                }
                            } catch (IllegalArgumentException e) {
                                System.out.println("\n[INPUT ERROR] " + e.getMessage());
                                System.out.print("Try again? (y/n): ");
                                String retryChoice = sc.nextLine().trim().toLowerCase();
                                if (!retryChoice.equals("y")) {
                                    break; // Exit do-while, return to main menu
                                }
                            } catch (Exception e) {
                                System.out.println("\n[UNEXPECTED ERROR] CLOSE failed: " + e.getMessage());
                                break;
                            } finally {
                                if (pw != null) Arrays.fill(pw, ' ');
                            }
                        } while (!success);
                        System.out.print("\nPress Enter to return to menu...");
                        sc.nextLine();
                        break;
                    }

                    case "3": { // DEPOSIT
                        clearScreen();
                        System.out.println("=== DEPOSIT (enter 'q' at any prompt to cancel) ===");
                        boolean success = false;
                        do {
                            String name = "";
                            int acc = 0;
                            char[] pw = null;
                            String curStr = "";
                            short cur = 0;
                            double amt = 0.0;
                            try {
                                System.out.print("name (or 'q' to cancel): ");
                                name = sc.nextLine().trim();
                                if (name.equalsIgnoreCase("q")) throw new IllegalArgumentException("Operation cancelled by user.");
                                if (name.isEmpty()) throw new IllegalArgumentException("Name cannot be empty.");
                                System.out.print("accountNo (or 'q' to cancel): ");
                                String accStr = sc.nextLine().trim();
                                if (accStr.equalsIgnoreCase("q")) throw new IllegalArgumentException("Operation cancelled by user.");
                                acc = Integer.parseInt(accStr);
                                if (acc <= 0) throw new IllegalArgumentException("Account number must be positive.");
                                pw = readPasswordWithCancel(sc, "password (1..16 bytes)");
                                if (pw == null) {
                                    throw new IllegalArgumentException("Operation cancelled by user.");
                                }
                                if (pw.length == 0 || pw.length > 16) {
                                    throw new IllegalArgumentException("Password length must be 1..16 bytes.");
                                }
                                System.out.print("currency (CNY/SGD, or 'q' to cancel): ");
                                curStr = sc.nextLine().trim().toUpperCase();
                                if (curStr.equals("Q")) {
                                    throw new IllegalArgumentException("Operation cancelled by user.");
                                }
                                cur = (short) (curStr.equals("SGD") ? Protocol.CUR_SGD : Protocol.CUR_CNY);
                                if (!curStr.equals("CNY") && !curStr.equals("SGD")) {
                                    throw new IllegalArgumentException("Invalid currency type. Please use CNY or SGD.");
                                }
                                System.out.print("amount (or 'q' to cancel): ");
                                String amtStr = sc.nextLine().trim();
                                if (amtStr.equalsIgnoreCase("q")) {
                                    throw new IllegalArgumentException("Operation cancelled by user.");
                                }
                                amt = Double.parseDouble(amtStr);
                                if (amt <= 0) {
                                    throw new IllegalArgumentException("Amount must be greater than 0.");
                                }

                                byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                ByteBuffer bb = ByteBuffer.allocate(2 + nameBytes.length + 4 + 16 + 2 + 8).order(ByteOrder.BIG_ENDIAN);
                                Protocol.putString(bb, name);
                                bb.putInt(acc);
                                Protocol.putPassword16(bb, pw);
                                bb.putShort(cur);
                                bb.putDouble(amt);
                                byte[] bodyBytes = Arrays.copyOf(bb.array(), bb.position());

                                Protocol.Message rep = call(Protocol.OP_DEPOSIT, bodyBytes);
                                if (rep.status != Protocol.STATUS_OK) {
                                    System.out.println("\n[ERROR] DEPOSIT failed: " + Protocol.statusToString(rep.status));
                                    throw new IllegalArgumentException("Operation failed on server: " + Protocol.statusToString(rep.status));
                                } else {
                                    ByteBuffer rb = ByteBuffer.wrap(rep.body).order(ByteOrder.BIG_ENDIAN);
                                    double bal = Double.longBitsToDouble(rb.getLong());
                                    System.out.println("\n[OK] Deposit completed.");
                                    printReceipt(
                                            "DEPOSIT",
                                            "SUCCESS",
                                            "Name: " + name,
                                            "AccountNo: " + acc,
                                            "Currency: " + curStr,
                                            "Amount: " + amt,
                                            "New balance: " + bal
                                    );
                                }
                                success = true;
                            } catch (CommunicationException e) {
                                System.out.println("\n[NETWORK ERROR] " + e.getMessage());
                                System.out.print("Retry this operation? (y/n): ");
                                String retryChoice = sc.nextLine().trim().toLowerCase();
                                if (!retryChoice.equals("y")) {
                                    break; // Exit do-while, return to main menu
                                }
                            } catch (IllegalArgumentException e) {
                                System.out.println("\n[INPUT ERROR] " + e.getMessage());
                                System.out.print("Try again? (y/n): ");
                                String retryChoice = sc.nextLine().trim().toLowerCase();
                                if (!retryChoice.equals("y")) {
                                    break; // Exit do-while, return to main menu
                                }
                            } catch (Exception e) {
                                System.out.println("\n[UNEXPECTED ERROR] DEPOSIT failed: " + e.getMessage());
                                break;
                            } finally {
                                if (pw != null) Arrays.fill(pw, ' ');
                            }
                        } while (!success);
                        System.out.print("\nPress Enter to return to menu...");
                        sc.nextLine();
                        break;
                    }

                    case "4": { // WITHDRAW
                        clearScreen();
                        System.out.println("=== WITHDRAW (enter 'q' at any prompt to cancel) ===");
                        boolean success = false;
                        do {
                            String name = "";
                            int acc = 0;
                            char[] pw = null;
                            String curStr = "";
                            short cur = 0;
                            double amt = 0.0;
                            try {
                                System.out.print("name (or 'q' to cancel): ");
                                name = sc.nextLine().trim();
                                if (name.equalsIgnoreCase("q")) throw new IllegalArgumentException("Operation cancelled by user.");
                                if (name.isEmpty()) throw new IllegalArgumentException("Name cannot be empty.");
                                System.out.print("accountNo (or 'q' to cancel): ");
                                String accStr = sc.nextLine().trim();
                                if (accStr.equalsIgnoreCase("q")) throw new IllegalArgumentException("Operation cancelled by user.");
                                acc = Integer.parseInt(accStr);
                                if (acc <= 0) throw new IllegalArgumentException("Account number must be positive.");
                                pw = readPasswordWithCancel(sc, "password (1..16 bytes)");
                                if (pw == null) {
                                    throw new IllegalArgumentException("Operation cancelled by user.");
                                }
                                if (pw.length == 0 || pw.length > 16) {
                                    throw new IllegalArgumentException("Password length must be 1..16 bytes.");
                                }
                                System.out.print("currency (CNY/SGD, or 'q' to cancel): ");
                                curStr = sc.nextLine().trim().toUpperCase();
                                if (curStr.equals("Q")) {
                                    throw new IllegalArgumentException("Operation cancelled by user.");
                                }
                                cur = (short) (curStr.equals("SGD") ? Protocol.CUR_SGD : Protocol.CUR_CNY);
                                if (!curStr.equals("CNY") && !curStr.equals("SGD")) {
                                    throw new IllegalArgumentException("Invalid currency type. Please use CNY or SGD.");
                                }
                                System.out.print("amount (or 'q' to cancel): ");
                                String amtStr = sc.nextLine().trim();
                                if (amtStr.equalsIgnoreCase("q")) {
                                    throw new IllegalArgumentException("Operation cancelled by user.");
                                }
                                amt = Double.parseDouble(amtStr);
                                if (amt <= 0) {
                                    throw new IllegalArgumentException("Amount must be greater than 0.");
                                }

                                byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                ByteBuffer bb = ByteBuffer.allocate(2 + nameBytes.length + 4 + 16 + 2 + 8).order(ByteOrder.BIG_ENDIAN);
                                Protocol.putString(bb, name);
                                bb.putInt(acc);
                                Protocol.putPassword16(bb, pw);
                                bb.putShort(cur);
                                bb.putDouble(amt);
                                byte[] bodyBytes = Arrays.copyOf(bb.array(), bb.position());

                                Protocol.Message rep = call(Protocol.OP_WITHDRAW, bodyBytes);
                                if (rep.status != Protocol.STATUS_OK) {
                                    System.out.println("\n[ERROR] WITHDRAW failed: " + Protocol.statusToString(rep.status));
                                    throw new IllegalArgumentException("Operation failed on server: " + Protocol.statusToString(rep.status));
                                } else {
                                    ByteBuffer rb = ByteBuffer.wrap(rep.body).order(ByteOrder.BIG_ENDIAN);
                                    double bal = Double.longBitsToDouble(rb.getLong());
                                    System.out.println("\n[OK] Withdrawal completed.");
                                    printReceipt(
                                            "WITHDRAW",
                                            "SUCCESS",
                                            "Name: " + name,
                                            "AccountNo: " + acc,
                                            "Currency: " + curStr,
                                            "Amount: " + amt,
                                            "New balance: " + bal
                                    );
                                }
                                success = true;
                            } catch (CommunicationException e) {
                                System.out.println("\n[NETWORK ERROR] " + e.getMessage());
                                System.out.print("Retry this operation? (y/n): ");
                                String retryChoice = sc.nextLine().trim().toLowerCase();
                                if (!retryChoice.equals("y")) {
                                    break; // Exit do-while, return to main menu
                                }
                            } catch (IllegalArgumentException e) {
                                System.out.println("\n[INPUT ERROR] " + e.getMessage());
                                System.out.print("Try again? (y/n): ");
                                String retryChoice = sc.nextLine().trim().toLowerCase();
                                if (!retryChoice.equals("y")) {
                                    break; // Exit do-while, return to main menu
                                }
                            } catch (Exception e) {
                                System.out.println("\n[UNEXPECTED ERROR] WITHDRAW failed: " + e.getMessage());
                                break;
                            } finally {
                                if (pw != null) Arrays.fill(pw, ' ');
                            }
                        } while (!success);
                        System.out.print("\nPress Enter to return to menu...");
                        sc.nextLine();
                        break;
                    }

                    case "5": { // QUERY
                        clearScreen();
                        System.out.println("=== QUERY Balance (enter 'q' at any prompt to cancel) ===");
                        boolean success = false;
                        do {
                            String name = "";
                            int acc = 0;
                            char[] pw = null;
                            try {
                                System.out.print("name (or 'q' to cancel): ");
                                name = sc.nextLine().trim();
                                if (name.equalsIgnoreCase("q")) throw new IllegalArgumentException("Operation cancelled by user.");
                                if (name.isEmpty()) throw new IllegalArgumentException("Name cannot be empty.");
                                System.out.print("accountNo (or 'q' to cancel): ");
                                String accStr = sc.nextLine().trim();
                                if (accStr.equalsIgnoreCase("q")) throw new IllegalArgumentException("Operation cancelled by user.");
                                acc = Integer.parseInt(accStr);
                                if (acc <= 0) throw new IllegalArgumentException("Account number must be positive.");
                                pw = readPasswordWithCancel(sc, "password (1..16 bytes)");
                                if (pw == null) {
                                    throw new IllegalArgumentException("Operation cancelled by user.");
                                }
                                if (pw.length == 0 || pw.length > 16) {
                                    throw new IllegalArgumentException("Password length must be 1..16 bytes.");
                                }

                                byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                ByteBuffer bb = ByteBuffer.allocate(2 + nameBytes.length + 4 + 16).order(ByteOrder.BIG_ENDIAN);
                                Protocol.putString(bb, name);
                                bb.putInt(acc);
                                Protocol.putPassword16(bb, pw);
                                byte[] bodyBytes = Arrays.copyOf(bb.array(), bb.position());

                                Protocol.Message rep = call(Protocol.OP_QUERY_BALANCE, bodyBytes);
                                if (rep.status != Protocol.STATUS_OK) {
                                    System.out.println("\n[ERROR] QUERY failed: " + Protocol.statusToString(rep.status));
                                    throw new IllegalArgumentException("Operation failed on server: " + Protocol.statusToString(rep.status));
                                } else {
                                    ByteBuffer rb = ByteBuffer.wrap(rep.body).order(ByteOrder.BIG_ENDIAN);
                                    int cur = Short.toUnsignedInt(rb.getShort());
                                    double bal = Double.longBitsToDouble(rb.getLong());
                                    System.out.println("\n[OK] Balance query successful.");
                                    printReceipt(
                                            "QUERY_BALANCE",
                                            "SUCCESS",
                                            "Name: " + name,
                                            "AccountNo: " + acc,
                                            "Currency: " + Protocol.currencyToString(cur),
                                            "Balance: " + bal
                                    );
                                }
                                success = true;
                            } catch (CommunicationException e) {
                                System.out.println("\n[NETWORK ERROR] " + e.getMessage());
                                System.out.print("Retry this operation? (y/n): ");
                                String retryChoice = sc.nextLine().trim().toLowerCase();
                                if (!retryChoice.equals("y")) {
                                    break; // Exit do-while, return to main menu
                                }
                            } catch (IllegalArgumentException e) {
                                System.out.println("\n[INPUT ERROR] " + e.getMessage());
                                System.out.print("Try again? (y/n): ");
                                String retryChoice = sc.nextLine().trim().toLowerCase();
                                if (!retryChoice.equals("y")) {
                                    break; // Exit do-while, return to main menu
                                }
                            } catch (Exception e) {
                                System.out.println("\n[UNEXPECTED ERROR] QUERY failed: " + e.getMessage());
                                break;
                            }
                            finally {
                                if (pw != null) Arrays.fill(pw, ' ');
                            }
                        } while (!success);
                        System.out.print("\nPress Enter to return to menu...");
                        sc.nextLine();
                        break;
                    }

                    case "6": { // TRANSFER
                        clearScreen();
                        System.out.println("=== TRANSFER (enter 'q' at any prompt to cancel) ===");
                        boolean success = false;
                        do {
                            String name = "";
                            int fromAcc = 0;
                            char[] pw = null;
                            int toAcc = 0;
                            String curStr = "";
                            short cur = 0;
                            double amt = 0.0;
                            try {
                                System.out.print("name (owner of FROM account, or 'q' to cancel): ");
                                name = sc.nextLine().trim();
                                if (name.equalsIgnoreCase("q")) throw new IllegalArgumentException("Operation cancelled by user.");
                                if (name.isEmpty()) throw new IllegalArgumentException("Name cannot be empty.");
                                System.out.print("fromAccountNo (or 'q' to cancel): ");
                                String fromAccStr = sc.nextLine().trim();
                                if (fromAccStr.equalsIgnoreCase("q")) throw new IllegalArgumentException("Operation cancelled by user.");
                                fromAcc = Integer.parseInt(fromAccStr);
                                if (fromAcc <= 0) throw new IllegalArgumentException("From account number must be positive.");
                                pw = readPasswordWithCancel(sc, "password (1..16 bytes)");
                                if (pw == null) {
                                    throw new IllegalArgumentException("Operation cancelled by user.");
                                }
                                if (pw.length == 0 || pw.length > 16) {
                                    throw new IllegalArgumentException("Password length must be 1..16 bytes.");
                                }
                                System.out.print("toAccountNo (or 'q' to cancel): ");
                                String toAccStr = sc.nextLine().trim();
                                if (toAccStr.equalsIgnoreCase("q")) {
                                    throw new IllegalArgumentException("Operation cancelled by user.");
                                }
                                toAcc = Integer.parseInt(toAccStr);
                                if (toAcc <= 0) throw new IllegalArgumentException("To account number must be positive.");
                                if (fromAcc == toAcc) throw new IllegalArgumentException("Cannot transfer to the same account.");
                                System.out.print("currency (CNY/SGD, or 'q' to cancel): ");
                                curStr = sc.nextLine().trim().toUpperCase();
                                if (curStr.equals("Q")) {
                                    throw new IllegalArgumentException("Operation cancelled by user.");
                                }
                                cur = (short) (curStr.equals("SGD") ? Protocol.CUR_SGD : Protocol.CUR_CNY);
                                if (!curStr.equals("CNY") && !curStr.equals("SGD")) {
                                    throw new IllegalArgumentException("Invalid currency type. Please use CNY or SGD.");
                                }
                                System.out.print("amount (or 'q' to cancel): ");
                                String amtStr = sc.nextLine().trim();
                                if (amtStr.equalsIgnoreCase("q")) {
                                    throw new IllegalArgumentException("Operation cancelled by user.");
                                }
                                amt = Double.parseDouble(amtStr);
                                if (amt <= 0) {
                                    throw new IllegalArgumentException("Amount must be greater than 0.");
                                }

                                byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                ByteBuffer bb = ByteBuffer.allocate(2 + nameBytes.length + 4 + 16 + 4 + 2 + 8).order(ByteOrder.BIG_ENDIAN);
                                Protocol.putString(bb, name);
                                bb.putInt(fromAcc);
                                Protocol.putPassword16(bb, pw);
                                bb.putInt(toAcc);
                                bb.putShort(cur);
                                bb.putDouble(amt);
                                byte[] bodyBytes = Arrays.copyOf(bb.array(), bb.position());

                                Protocol.Message rep = call(Protocol.OP_TRANSFER, bodyBytes);
                                if (rep.status != Protocol.STATUS_OK) {
                                    System.out.println("\n[ERROR] TRANSFER failed: " + Protocol.statusToString(rep.status));
                                    throw new IllegalArgumentException("Operation failed on server: " + Protocol.statusToString(rep.status));
                                } else {
                                    ByteBuffer rb = ByteBuffer.wrap(rep.body).order(ByteOrder.BIG_ENDIAN);
                                    double fromBal = Double.longBitsToDouble(rb.getLong());
                                    double toBal = Double.longBitsToDouble(rb.getLong());
                                    System.out.println("\n[OK] Transfer completed.");
                                    printReceipt(
                                            "TRANSFER",
                                            "SUCCESS",
                                            "Name: " + name,
                                            "FromAccountNo: " + fromAcc,
                                            "ToAccountNo: " + toAcc,
                                            "Currency: " + curStr,
                                            "Amount: " + amt,
                                            "From new balance: " + fromBal,
                                            "To new balance: " + toBal
                                    );
                                }
                                success = true;
                            } catch (CommunicationException e) {
                                System.out.println("\n[NETWORK ERROR] " + e.getMessage());
                                System.out.print("Retry this operation? (y/n): ");
                                String retryChoice = sc.nextLine().trim().toLowerCase();
                                if (!retryChoice.equals("y")) {
                                    break; // Exit do-while, return to main menu
                                }
                            } catch (IllegalArgumentException e) {
                                System.out.println("\n[INPUT ERROR] " + e.getMessage());
                                System.out.print("Try again? (y/n): ");
                                String retryChoice = sc.nextLine().trim().toLowerCase();
                                if (!retryChoice.equals("y")) {
                                    break; // Exit do-while, return to main menu
                                }
                            } catch (Exception e) {
                                System.out.println("\n[UNEXPECTED ERROR] TRANSFER failed: " + e.getMessage());
                                break;
                            } finally {
                                if (pw != null) Arrays.fill(pw, ' ');
                            }
                        } while (!success);
                        System.out.print("\nPress Enter to return to menu...");
                        sc.nextLine();
                        break;
                    }

                    case "7": { // MONITOR
                        clearScreen();
                        System.out.println("=== MONITOR (enter 'q' to cancel) ===");
                        boolean success = false;
                        do {
                            int sec = 0;
                            try {
                                System.out.print("monitor seconds (or 'q' to cancel): ");
                                String secStr = sc.nextLine().trim();
                                if (secStr.equalsIgnoreCase("q")) {
                                    throw new IllegalArgumentException("Operation cancelled by user.");
                                }
                                sec = Integer.parseInt(secStr);
                                if (sec <= 0) {
                                    throw new IllegalArgumentException("Monitor seconds must be greater than 0.");
                                }

                                ByteBuffer bb = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN);
                                bb.putShort((short) sec);
                                byte[] bodyBytes = Arrays.copyOf(bb.array(), bb.position());

                                Protocol.Message rep = call(Protocol.OP_MONITOR_REGISTER, bodyBytes);
                                if (rep.status != Protocol.STATUS_OK) {
                                    System.out.println("\n[ERROR] MONITOR register failed: " + Protocol.statusToString(rep.status));
                                    throw new IllegalArgumentException("Operation failed on server: " + Protocol.statusToString(rep.status));
                                }
                                ByteBuffer rb = ByteBuffer.wrap(rep.body).order(ByteOrder.BIG_ENDIAN);
                                String msg = Protocol.getString(rb);
                                System.out.println("\n[OK] Monitor registered: " + msg);

                                System.out.println("== Waiting for account update callbacks for " + sec + " seconds (client blocked) ==");
                                long end = System.currentTimeMillis() + sec * 1000L;
                                sock.setSoTimeout(1000); // poll
                                while (System.currentTimeMillis() < end) {
                                    try {
                                        byte[] buf = new byte[2048];
                                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                                        sock.receive(pkt);
                                        Protocol.Message m = Protocol.decode(pkt.getData(), pkt.getLength());
                                        if (m == null) continue;
                                        if (m.msgType != Protocol.MSG_CALLBACK) continue;
                                        if (m.opCode != Protocol.OP_CALLBACK_UPDATE) continue;

                                        ByteBuffer cb = ByteBuffer.wrap(m.body).order(ByteOrder.BIG_ENDIAN);
                                        int updateType = Short.toUnsignedInt(cb.getShort());
                                        int accNo = cb.getInt();
                                        int cur = Short.toUnsignedInt(cb.getShort());
                                        double newBal = Double.longBitsToDouble(cb.getLong());
                                        String info = Protocol.getString(cb);

                                        System.out.println("[CALLBACK] type=" + Protocol.opCodeToString(updateType)
                                                + " acc=" + accNo
                                                + " cur=" + Protocol.currencyToString(cur)
                                                + " newBal=" + newBal
                                                + " info=" + info);
                                    } catch (SocketTimeoutException e) {
                                        // keep waiting
                                    } catch (Exception e) {
                                        System.out.println("Error processing callback: " + e.getMessage());
                                    }
                                }
                                success = true;
                            } catch (CommunicationException e) {
                                System.out.println("\n[NETWORK ERROR] " + e.getMessage());
                                System.out.print("Retry this operation? (y/n): ");
                                String retryChoice = sc.nextLine().trim().toLowerCase();
                                if (!retryChoice.equals("y")) { break; }
                            } catch (IllegalArgumentException e) {
                                System.out.println("\n[INPUT ERROR] " + e.getMessage());
                                System.out.print("Try again? (y/n): ");
                                String retryChoice = sc.nextLine().trim().toLowerCase();
                                if (!retryChoice.equals("y")) { break; }
                            } catch (Exception e) {
                                System.out.println("\n[UNEXPECTED ERROR] MONITOR failed: " + e.getMessage());
                                break;
                            }
                        } while (!success);
                        System.out.println("== Monitor finished ==\n");
                        System.out.print("Press Enter to return to menu...");
                        sc.nextLine();
                        break;
                    }

                    default:
                        System.out.println("\n[INPUT ERROR] Unknown menu option.");
                        System.out.print("Press Enter to return to menu...");
                        sc.nextLine();
                        break;
                }
            }
        }
    }
}