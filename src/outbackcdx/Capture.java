package outbackcdx;

import org.apache.commons.codec.binary.Base32;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;

/**
 * A CDX record which can be encoded to a reasonable space-efficient packed representation.
 *
 * Records are encoded as two byte arrays called the key and the value.  The record's key is designed to be bytewise
 * sorted and is simply the urlkey concatenated with the timestamp as a big-endian 64-bit value.
 *
 * <pre>
 *     0              urlkey.length                 urlkey.size + 8
 *     +--------------+-----------------------------+
 *     | ASCII urlkey | 64-bit big-endian timestamp |
 *     +--------------+-----------------------------+
 * </pre>
 *
 * The record's consists of a static list fields packed using {@link outbackcdx.VarInt}.  The first field in the
 * value is a schema version number to allow fields to be added or removed in later versions.
 */
public class Capture {
    private static int CURRENT_VERSION = 1;
    static final DateTimeFormatter arcTimeFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    static final Base32 base32 = new Base32();

    public String urlkey;
    public long timestamp;
    public String original;
    public String mimetype;
    public int status;
    byte[] digest;
    public long length;
    public String file;
    public long compressedoffset;
    public String redirecturl;

    public Capture(Map.Entry<byte[], byte[]> entry) {
        this(entry.getKey(), entry.getValue());
    }

    public Capture(byte[] key, byte[] value) {
        decodeKey(key);
        decodeValue(ByteBuffer.wrap(value));
    }

    public Capture() {
    }

    public void decodeKey(byte[] key) {
        urlkey = new String(key, 0, key.length - 8, StandardCharsets.US_ASCII);
        ByteBuffer keyBuf = ByteBuffer.wrap(key);
        keyBuf.order(ByteOrder.BIG_ENDIAN);
        timestamp = keyBuf.getLong(key.length - 8);
    }

    public static byte[] encodeKey(String keyurl, long timestamp) {
        byte[] urlBytes = keyurl.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer bb = ByteBuffer.allocate(urlBytes.length + 8);
        bb.order(ByteOrder.BIG_ENDIAN);
        bb.put(urlBytes);
        bb.putLong(timestamp);
        return bb.array();
    }

    /**
     * Primary key used for indexing by URL and timestamp.
     */
    public byte[] encodeKey() {
        return encodeKey(urlkey, timestamp);
    }

    /**
     * Key of the secondary index for lookup by digest.
     */
    public byte[] encodeDigestKey() {
        byte[] primaryKey = encodeKey();
        ByteBuffer bb = ByteBuffer.allocate(primaryKey.length + 1 + digest.length);
        bb.put((byte) digest.length);
        bb.put(digest);
        bb.put(primaryKey);
        return bb.array();
    }

    /**
     * Key of the secondary index for lookup by digest.
     */
    public static byte[] encodeDigestKeyPrefix(byte[] digest) {
        byte[] key = new byte[1 + digest.length];
        key[0] = (byte) digest.length;
        System.arraycopy(digest, 0, key, 1, digest.length);
        return key;
    }

    static Capture fromDigestKey(byte[] digestKey, byte[] value) {
        return new Capture(digestKeyToPrimaryKey(digestKey), value);
    }

    private static byte[] digestKeyToPrimaryKey(byte[] digestKey) {
        int digestLength = digestKey[0];
        return Arrays.copyOfRange(digestKey, 1 + digestLength, digestKey.length);
    }

    public void decodeValue(ByteBuffer bb) {
        int version = (int)VarInt.decode(bb);
        switch (version) {
            case 0: decodeValueV0(bb); break;
            case 1: decodeValueV1(bb); break;
            default:
                throw new IllegalArgumentException("CDX encoding is too new (v" + version + ") only versions up to v"
                        + CURRENT_VERSION + " are supported");
        }
    }

    private void decodeValueV0(ByteBuffer bb) {
        original = VarInt.decodeAscii(bb);
        status = (int) VarInt.decode(bb);
        mimetype = VarInt.decodeAscii(bb);
        length = VarInt.decode(bb);
        digest = base32.decode(VarInt.decodeAscii(bb));
        file = VarInt.decodeAscii(bb);
        compressedoffset = VarInt.decode(bb);
        redirecturl = VarInt.decodeAscii(bb);
    }

    private void decodeValueV1(ByteBuffer bb) {
        original = VarInt.decodeAscii(bb);
        status = (int) VarInt.decode(bb);
        mimetype = VarInt.decodeAscii(bb);
        length = VarInt.decode(bb);
        digest = VarInt.decodeBytes(bb);
        file = VarInt.decodeAscii(bb);
        compressedoffset = VarInt.decode(bb);
        redirecturl = VarInt.decodeAscii(bb);
    }

    public int sizeValue() {
        return VarInt.size(CURRENT_VERSION) +
                VarInt.sizeAscii(original) +
                VarInt.size(status) +
                VarInt.sizeAscii(mimetype) +
                VarInt.size(length) +
                VarInt.sizeBytes(digest) +
                VarInt.sizeAscii(file) +
                VarInt.size(compressedoffset) +
                VarInt.sizeAscii(redirecturl);
    }

    public byte[] encodeValue() {
        ByteBuffer bb = ByteBuffer.allocate(sizeValue());
        VarInt.encode(bb, CURRENT_VERSION);
        VarInt.encodeAscii(bb, original);
        VarInt.encode(bb, status);
        VarInt.encodeAscii(bb, mimetype);
        VarInt.encode(bb, length);
        VarInt.encodeBytes(bb, digest);
        VarInt.encodeAscii(bb, file);
        VarInt.encode(bb, compressedoffset);
        VarInt.encodeAscii(bb, redirecturl);
        return bb.array();
    }

    /**
     * Format as a CDX11 line.
     */
    public String toString() {
        StringBuilder out = new StringBuilder();
        out.append(urlkey).append(' ');
        out.append(Long.toString(timestamp)).append(' ');
        out.append(original).append(' ');
        out.append(mimetype).append(' ');
        out.append(Integer.toString(status)).append(' ');
        out.append(digestBase32()).append(' ');
        out.append(redirecturl).append(' ');
        out.append("- "); // TODO robots
        out.append(Long.toString(length)).append(' ');
        out.append(compressedoffset).append(' ');
        out.append(file);
        return out.toString();
    }

    public static Capture fromCdxLine(String line) {
        String[] fields = line.split(" ");
        Capture capture = new Capture();
        capture.timestamp = Long.parseLong(fields[1]);
        capture.original = fields[2];
        capture.urlkey = UrlCanonicalizer.surtCanonicalize(capture.original);
        capture.mimetype = fields[3];
        capture.status = fields[4].equals("-") ? 0 : Integer.parseInt(fields[4]);
        capture.digest = base32.decode(fields[5]);
        capture.redirecturl = fields[6];

        if (fields.length >= 11) { // 11 fields: CDX N b a m s k r M S V g
            // TODO robots = fields[7]
            capture.length = fields[8].equals("-") ? 0 : Long.parseLong(fields[8]);
            capture.compressedoffset = Long.parseLong(fields[9]);
            capture.file = fields[10];
        } else { // 9 fields: CDX N b a m s k r V g
            capture.compressedoffset = Long.parseLong(fields[7]);
            capture.file = fields[8];
        }
        return capture;
    }

    public Date date() {
        return parseTimestamp(timestamp);
    }

    public static Date parseTimestamp(long timestamp) {
        return Date.from(LocalDateTime.parse(Long.toString(timestamp), arcTimeFormat).toInstant(ZoneOffset.UTC));
    }

    String digestBase32() {
        return base32.encodeAsString(digest);
    }
}
