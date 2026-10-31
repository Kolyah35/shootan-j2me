import java.io.*;

public class Utils {
    static public int readIntLE(InputStream in) throws IOException {
        byte b0 = (byte) in.read();
        byte b1 = (byte) in.read();
        byte b2 = (byte) in.read();
        byte b3 = (byte) in.read();

//        if ((b0 | b1 | b2 | b3) < 0) {
//            throw new EOFException();
//        }

        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    static public int readShortLE(InputStream in) throws IOException {
        byte b0 = (byte) in.read();
        byte b1 = (byte) in.read();

//        if ((b0 | b1) < 0) {
//            throw new EOFException();
//        }

        return b0 | (b1 << 8);
    }

    public static float readFloatLE(InputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();

//        if ((b0 | b1 | b2 | b3) < 0) {
//            throw new EOFException();
//        }

        int bits = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);

        return Float.intBitsToFloat(bits);
    }

    public static void writeFloatLE(OutputStream out, float value) throws IOException {
        int bits = Float.floatToIntBits(value);

        out.write(bits & 0xFF);
        out.write((bits >>> 8) & 0xFF);
        out.write((bits >>> 16) & 0xFF);
        out.write((bits >>> 24) & 0xFF);
    }

    public static void writeFloatLE(byte[] data, int offset, float value) {
        int bits = Float.floatToIntBits(value);

        data[offset]     = (byte)bits;
        data[offset + 1] = (byte)(bits >>> 8);
        data[offset + 2] = (byte)(bits >>> 16);
        data[offset + 3] = (byte)(bits >>> 24);
    }
}
