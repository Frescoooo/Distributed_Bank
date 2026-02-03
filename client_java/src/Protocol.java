import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class Protocol {
    public static final int MAGIC = 0x42414E4B; // BANK
    public static final byte VERSION = 1;

    public static final byte MSG_REQUEST = 1;
    public static final byte MSG_REPLY = 2;
    public static final byte MSG_CALLBACK = 3;

    public static final short OP_OPEN = 1;
    public static final short OP_CLOSE = 2;
    public static final short OP_DEPOSIT = 3;
    public static final short OP_WITHDRAW = 4;
    public static final short OP_MONITOR_REGISTER = 5;
    public static final short OP_QUERY_BALANCE = 6;
    public static final short OP_TRANSFER = 7;
    public static final short OP_CALLBACK_UPDATE = 100;

    public static final short FLAG_AT_MOST_ONCE = 0x0001;

    public static final short STATUS_OK = 0;
    public static final short STATUS_ERR_BAD_REQUEST = 1;
    public static final short STATUS_ERR_AUTH = 2;
    public static final short STATUS_ERR_NOT_FOUND = 3;
    public static final short STATUS_ERR_CURRENCY = 4;
    public static final short STATUS_ERR_INSUFFICIENT_FUNDS = 5;
    public static final short STATUS_ERR_PASSWORD_FORMAT = 6;

    public static final short CUR_CNY = 0;
    public static final short CUR_SGD = 1;

    public static class Message {
        public int magic;
        public byte version;
        public byte msgType;
        public short opCode;
        public short flags;
        public short status;
        public long requestId;
        public int bodyLen;
        public byte[] body;
    }

    // Header: 24 bytes (magic:4 + version:1 + msgType:1 + opCode:2 + flags:2 + status:2 + requestId:8 + bodyLen:4)
    public static final int HEADER_SIZE = 24;

    public static byte[] encode(Message m) {
        m.bodyLen = (m.body == null) ? 0 : m.body.length;
        ByteBuffer bb = ByteBuffer.allocate(HEADER_SIZE + m.bodyLen).order(ByteOrder.BIG_ENDIAN);
        bb.putInt(m.magic);
        bb.put(m.version);
        bb.put(m.msgType);
        bb.putShort(m.opCode);
        bb.putShort(m.flags);
        bb.putShort(m.status);
        bb.putLong(m.requestId);
        bb.putInt(m.bodyLen);
        if (m.bodyLen > 0) bb.put(m.body);
        return bb.array();
    }

    public static Message decode(byte[] raw, int len) {
        if (len < HEADER_SIZE) return null;
        ByteBuffer bb = ByteBuffer.wrap(Arrays.copyOf(raw, len)).order(ByteOrder.BIG_ENDIAN);
        Message m = new Message();
        m.magic = bb.getInt();
        if (m.magic != MAGIC) return null;
        m.version = bb.get();
        m.msgType = bb.get();
        m.opCode = bb.getShort();
        m.flags = bb.getShort();
        m.status = bb.getShort();
        m.requestId = bb.getLong();
        m.bodyLen = bb.getInt();
        if (m.bodyLen < 0 || bb.remaining() < m.bodyLen) return null;
        m.body = new byte[m.bodyLen];
        bb.get(m.body);
        return m;
    }

    // body writers
    public static void putString(ByteBuffer bb, String s) {
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        if (data.length > 65535) throw new IllegalArgumentException("string too long");
        bb.putShort((short) data.length);
        bb.put(data);
    }

    public static void putPassword16(ByteBuffer bb, char[] pw) {
        byte[] data = new String(pw).getBytes(StandardCharsets.UTF_8);
        if (data.length == 0 || data.length > 16) throw new IllegalArgumentException("password length must be 1..16 bytes");
        byte[] fixed = new byte[16];
        System.arraycopy(data, 0, fixed, 0, data.length);
        bb.put(fixed);
    }

    // body readers
    public static String getString(ByteBuffer bb) {
        int len = Short.toUnsignedInt(bb.getShort());
        byte[] data = new byte[len];
        bb.get(data);
        return new String(data, StandardCharsets.UTF_8);
    }

    public static String getPassword16(ByteBuffer bb) {
        byte[] fixed = new byte[16];
        bb.get(fixed);
        int n = 16;
        while (n > 0 && fixed[n - 1] == 0) n--;
        return new String(fixed, 0, n, StandardCharsets.UTF_8);
    }

    public static String currencyToString(int c) {
        return (c == CUR_CNY) ? "CNY" : (c == CUR_SGD ? "SGD" : "UNKNOWN");
    }

    public static String statusToString(int s) {
        switch (s) {
            case STATUS_OK: return "OK";
            case STATUS_ERR_BAD_REQUEST: return "Request format error (BAD_REQUEST)";
            case STATUS_ERR_AUTH: return "Authentication failed: name/account/password mismatch (AUTH)";
            case STATUS_ERR_NOT_FOUND: return "Account not found or already closed (NOT_FOUND)";
            case STATUS_ERR_CURRENCY: return "Currency mismatch (CURRENCY)";
            case STATUS_ERR_INSUFFICIENT_FUNDS: return "ERR_INSUFFICIENT_FUNDS";
            case STATUS_ERR_PASSWORD_FORMAT: return "Password format error: must be 1..16 bytes (PASSWORD_FORMAT)";
            default: return "Unknown error status=" + s;
        }
    }

    public static String opCodeToString(int op) {
        switch (op) {
            case OP_OPEN: return "OPEN";
            case OP_CLOSE: return "CLOSE";
            case OP_DEPOSIT: return "DEPOSIT";
            case OP_WITHDRAW: return "WITHDRAW";
            case OP_MONITOR_REGISTER: return "MONITOR_REGISTER";
            case OP_QUERY_BALANCE: return "QUERY_BALANCE";
            case OP_TRANSFER: return "TRANSFER";
            case OP_CALLBACK_UPDATE: return "CALLBACK_UPDATE";
            default: return "UNKNOWN(" + op + ")";
        }
    }
}