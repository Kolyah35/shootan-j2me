package enet;

/**
 * Protocol constants and big-endian helpers for ENet 1.3.x wire format.
 * CLDC 1.1 compatible: no generics, no autoboxing, no java.nio.
 */
public final class ENet {
    private ENet() {}

    public static final int MIN_MTU = 576;
    public static final int MAX_MTU = 4096;
    public static final int DEFAULT_MTU = 1400;
    public static final int MIN_WINDOW_SIZE = 4096;
    public static final int MAX_WINDOW_SIZE = 65536;
    public static final int MAX_CHANNELS = 255;
    public static final int MAX_PEER_ID = 0x0FFF;

    public static final int HEADER_FLAG_COMPRESSED = 0x4000;
    public static final int HEADER_FLAG_SENT_TIME = 0x8000;
    public static final int HEADER_SESSION_MASK = 0x3000;
    public static final int HEADER_SESSION_SHIFT = 12;

    public static final int CMD_NONE = 0;
    public static final int CMD_ACK = 1;
    public static final int CMD_CONNECT = 2;
    public static final int CMD_VERIFY_CONNECT = 3;
    public static final int CMD_DISCONNECT = 4;
    public static final int CMD_PING = 5;
    public static final int CMD_SEND_RELIABLE = 6;
    public static final int CMD_SEND_UNRELIABLE = 7;
    public static final int CMD_SEND_FRAGMENT = 8;
    public static final int CMD_SEND_UNSEQUENCED = 9;
    public static final int CMD_BANDWIDTH_LIMIT = 10;
    public static final int CMD_THROTTLE_CONFIGURE = 11;
    public static final int CMD_SEND_UNRELIABLE_FRAGMENT = 12;

    public static final int CMD_MASK = 0x0F;
    public static final int CMD_FLAG_ACK = 0x80;
    public static final int CMD_FLAG_UNSEQUENCED = 0x40;

    /** Fixed size of each command (without payload), indexed by command number. */
    public static final int[] COMMAND_SIZE = {
        0, 8, 48, 44, 8, 4, 6, 8, 24, 8, 12, 16, 24
    };

    // ---- big-endian helpers ----

    public static int getU16(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    public static int getU32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16)
             | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    public static void putU16(byte[] b, int o, int v) {
        b[o] = (byte) (v >> 8);
        b[o + 1] = (byte) v;
    }

    public static void putU32(byte[] b, int o, int v) {
        b[o] = (byte) (v >> 24);
        b[o + 1] = (byte) (v >> 16);
        b[o + 2] = (byte) (v >> 8);
        b[o + 3] = (byte) v;
    }

    // ---- CRC32 (same polynomial/params as enet_crc32: reflected 0x04C11DB7) ----

    private static final int[] CRC_TABLE = new int[256];

    static {
        for (int n = 0; n < 256; n++) {
            int c = n;
            for (int k = 0; k < 8; k++) {
                if ((c & 1) != 0) c = (c >>> 1) ^ 0xEDB88320;
                else c = c >>> 1;
            }
            CRC_TABLE[n] = c;
        }
    }

    /** Standard CRC-32. Written big-endian on the wire when checksums are on. */
    public static int crc32(byte[] data, int off, int len) {
        int crc = 0xFFFFFFFF;
        for (int i = off; i < off + len; i++) {
            crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ data[i]) & 0xFF];
        }
        return ~crc;
    }
}
