package outbackcdx;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Protobuf-style base-128 variable length integer DEFAULT_ENCODING.
 *
 * VarInts are encoded little-endian. The most signficant bit of each byte is a flag that indicates whether the VarInt
 * continues for subsequent bytes.  An VarInt which requires n bytes to be encoded would thus look like:
 *
 * <pre>
 *     8   7                           0
 *     +---+---------------------------+
 *     | 1 | x & 127                   |  byte 0
 *     +---+---------------------------+
 *     | 1 | (x >>> 7) & 127           |  byte 1
 *     +---+---------------------------+
 *     :   :                           :    :
 *     +---+---------------------------+
 *     | 1 | (x >>> 7 * (n - 1)) & 127 |  byte (n - 1)
 *     +---|---------------------------+
 *     | 0 | (x >>> 7 * n) & 127       |  byte n
 *     +---+---------------------------+
 * </pre>
 *
 * ASCII strings and byte arrays are stored by prefixing with a VarInt indicating their length.
 */
public class VarInt {
    private VarInt() {}

    public static int sizeAscii(String s) {
        return size(s.length()) + s.length();
    }

    public static void encodeAscii(ByteBuffer bb, String s) {
        encodeBytes(bb, s.getBytes(StandardCharsets.US_ASCII));
    }

    public static String decodeAscii(ByteBuffer bb) {
        return new String(decodeBytes(bb), StandardCharsets.US_ASCII);
    }

    public static int sizeBytes(byte[] bytes) {
        return size(bytes.length) + bytes.length;
    }

    public static void encodeBytes(ByteBuffer bb, byte[] bytes) {
        encode(bb, bytes.length);
        bb.put(bytes);
    }

    public static byte[] decodeBytes(ByteBuffer bb) {
        long len = decode(bb);
        byte[] bytes = new byte[(int)len];
        bb.get(bytes);
        return bytes;
    }

    public static int size(long x) {
        int size = 1;
        while (Long.compareUnsigned(x, 127) > 0) {
            size++;
            x >>>= 7;
        }
        return size;
    }

    public static void encode(ByteBuffer bb, long x) {
        while (Long.compareUnsigned(x, 127) > 0) {
            bb.put((byte) (x & 127 | 128));
            x >>>= 7;
        }
        bb.put((byte) (x & 127));
    }

    public static long decode(ByteBuffer bb) {
        long x = 0;
        int shift = 0;
        long b;
        do {
            b = bb.get() & 0xff;
            x |= (b & 127) << shift;
            shift += 7;
        } while ((b & 128) != 0);
        return x;
    }
}
