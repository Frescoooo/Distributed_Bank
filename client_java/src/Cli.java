import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.Scanner;

public class Cli {
    private final DatagramSocket sock;
    private final InetAddress serverIp;
    private final int serverPort;
    private final boolean atMostOnce;
    private final int timeoutMs;
    private final int retry;
    private final Random rnd = new Random();

    public Cli(DatagramSocket sock, InetAddress serverIp, int serverPort, boolean atMostOnce, int timeoutMs, int retry) {
        this.sock = sock;
        this.serverIp = serverIp;
        this.serverPort = serverPort;
        this.atMostOnce = atMostOnce;
        this.timeoutMs = timeoutMs;
        this.retry = retry;
    }

    private Protocol.Message call(short opCode, byte[] body) throws Exception {
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
        DatagramPacket p = new DatagramPacket(out, out.length, serverIp, serverPort);

        sock.setSoTimeout(timeoutMs);

        for (int attempt = 1; attempt <= retry; attempt++) {
            sock.send(p);
            try {
                byte[] buf = new byte[2048];
                DatagramPacket rp = new DatagramPacket(buf, buf.length);
                sock.receive(rp);
                Protocol.Message rep = Protocol.decode(rp.getData(), rp.getLength());
                if (rep == null) continue;
                if (rep.msgType != Protocol.MSG_REPLY) continue;
                if (rep.requestId != reqId) continue; // ignore unrelated (e.g. callbacks)
                return rep;
            } catch (SocketTimeoutException e) {
                System.out.println("[client] timeout, retry " + attempt + "/" + retry);
            }
        }
        throw new RuntimeException("request failed after retries");
    }

    public void run() throws Exception {
        // Use try-with-resources to avoid "Scanner not closed" warning
        try (Scanner sc = new Scanner(System.in)) {
            while (true) {
                System.out.println("\n== Menu ==");
                System.out.println("1) OPEN account");
                System.out.println("2) QUERY balance (idempotent)");
                System.out.println("3) MONITOR register (callback)");
                System.out.println("0) EXIT");
                System.out.print("Choose: ");
                String choice = sc.nextLine().trim();

                switch (choice) {
                    case "0":
                        System.out.println("Bye.");
                        return;

                    case "1": {
                        System.out.print("name: ");
                        String name = sc.nextLine().trim();
                        System.out.print("password (1..16 bytes): ");
                        String pw = sc.nextLine();
                        System.out.print("currency (CNY/SGD): ");
                        String curStr = sc.nextLine().trim().toUpperCase();
                        short cur = curStr.equals("SGD") ? Protocol.CUR_SGD : Protocol.CUR_CNY;
                        System.out.print("initial balance: ");
                        double init = Double.parseDouble(sc.nextLine().trim());

                        byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        ByteBuffer bb = ByteBuffer.allocate(2 + nameBytes.length + 16 + 2 + 8).order(ByteOrder.BIG_ENDIAN);
                        Protocol.putString(bb, name);
                        Protocol.putPassword16(bb, pw);
                        bb.putShort(cur);
                        bb.putLong(Double.doubleToLongBits(init));

                        Protocol.Message rep = call(Protocol.OP_OPEN, bb.array());
                        if (rep.status != Protocol.STATUS_OK) {
                            System.out.println("OPEN failed, status=" + rep.status);
                        } else {
                            ByteBuffer rb = ByteBuffer.wrap(rep.body).order(ByteOrder.BIG_ENDIAN);
                            int accNo = rb.getInt();
                            double bal = Double.longBitsToDouble(rb.getLong());
                            System.out.println("OPEN OK. accountNo=" + accNo + " balance=" + bal);
                        }
                        break;
                    }

                    case "2": {
                        System.out.print("name: ");
                        String name = sc.nextLine().trim();
                        System.out.print("accountNo: ");
                        int acc = Integer.parseInt(sc.nextLine().trim());
                        System.out.print("password (1..16 bytes): ");
                        String pw = sc.nextLine();

                        byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        ByteBuffer bb = ByteBuffer.allocate(2 + nameBytes.length + 4 + 16).order(ByteOrder.BIG_ENDIAN);
                        Protocol.putString(bb, name);
                        bb.putInt(acc);
                        Protocol.putPassword16(bb, pw);

                        Protocol.Message rep = call(Protocol.OP_QUERY_BALANCE, bb.array());
                        if (rep.status != Protocol.STATUS_OK) {
                            System.out.println("QUERY failed, status=" + rep.status);
                        } else {
                            ByteBuffer rb = ByteBuffer.wrap(rep.body).order(ByteOrder.BIG_ENDIAN);
                            int cur = Short.toUnsignedInt(rb.getShort());
                            double bal = Double.longBitsToDouble(rb.getLong());
                            System.out.println("BALANCE: " + bal + " " + Protocol.currencyToString(cur));
                        }
                        break;
                    }

                    case "3": {
                        System.out.print("monitor seconds: ");
                        int sec = Integer.parseInt(sc.nextLine().trim());
                        ByteBuffer bb = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN);
                        bb.putShort((short) sec);

                        Protocol.Message rep = call(Protocol.OP_MONITOR_REGISTER, bb.array());
                        if (rep.status != Protocol.STATUS_OK) {
                            System.out.println("MONITOR failed, status=" + rep.status);
                            break;
                        }
                        ByteBuffer rb = ByteBuffer.wrap(rep.body).order(ByteOrder.BIG_ENDIAN);
                        String msg = Protocol.getString(rb);
                        System.out.println("MONITOR OK: " + msg);

                        System.out.println("== Waiting callbacks for " + sec + " seconds (client blocked) ==");
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

                                System.out.println("[CALLBACK] type=" + updateType + " acc=" + accNo +
                                        " cur=" + Protocol.currencyToString(cur) + " newBal=" + newBal +
                                        " info=" + info);
                            } catch (SocketTimeoutException e) {
                                // keep waiting
                            }
                        }
                        System.out.println("== Monitor finished ==");
                        break;
                    }

                    default:
                        System.out.println("Unknown option");
                        break;
                }
            }
        }
    }
}