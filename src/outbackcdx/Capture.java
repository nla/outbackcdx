package outbackcdx;

import org.apache.commons.codec.binary.Base32;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Map;

/**
 * A CDX record which can be encoded to a reasonable space-efficient packed representation.
 * <p>
 * Records are encoded as two byte arrays called the key and the value.  The record's key is designed to be bytewise
 * sorted and is simply the urlkey concatenated with the timestamp as a big-endian 64-bit value.
 *
 * <pre>
 *     0              urlkey.length                 urlkey.size + 8
 *     +--------------+-----------------------------+
 *     | ASCII urlkey | 64-bit big-endian timestamp |
 *     +--------------+-----------------------------+
 * </pre>
 * <p>
 * The record's consists of a static list fields packed using {@link outbackcdx.VarInt}.  The first field in the
 * value is a schema version number to allow fields to be added or removed in later versions.
 */
public class Capture {
    private static int CURRENT_VERSION = 2;
    static final DateTimeFormatter arcTimeFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    static final Base32 base32 = new Base32();

    public String urlkey;
    public long timestamp;
    public String original;
    public String mimetype;
    public int status;
    public String digest;
    public long length;
    public String file;
    public long compressedoffset;
    public String redirecturl;
    public String robotflags;

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

    public byte[] encodeKey() {
        return encodeKey(urlkey, timestamp);
    }

    public void decodeValue(ByteBuffer bb) {
        int version = (int) VarInt.decode(bb);
        switch (version) {
            case 0:
                decodeValueV0(bb);
                break;
            case 1:
                decodeValueV1(bb);
                break;
            case 2:
                decodeValueV2(bb);
                break;
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
        digest = VarInt.decodeAscii(bb);
        file = VarInt.decodeAscii(bb);
        compressedoffset = VarInt.decode(bb);
        redirecturl = VarInt.decodeAscii(bb);
        robotflags = "-";
    }

    private void decodeValueV1(ByteBuffer bb) {
        original = VarInt.decodeAscii(bb);
        status = (int) VarInt.decode(bb);
        mimetype = VarInt.decodeAscii(bb);
        length = VarInt.decode(bb);
        digest = base32.encodeAsString(VarInt.decodeBytes(bb));
        file = VarInt.decodeAscii(bb);
        compressedoffset = VarInt.decode(bb);
        redirecturl = VarInt.decodeAscii(bb);
        robotflags = "-";
    }

    private void decodeValueV2(ByteBuffer bb) {
        decodeValueV1(bb);
        robotflags = VarInt.decodeAscii(bb);
    }

    public int sizeValue() {
        return VarInt.size(CURRENT_VERSION) +
                VarInt.sizeAscii(original) +
                VarInt.size(status) +
                VarInt.sizeAscii(mimetype) +
                VarInt.size(length) +
                VarInt.sizeBytes(base32.decode(digest)) +
                VarInt.sizeAscii(file) +
                VarInt.size(compressedoffset) +
                VarInt.sizeAscii(redirecturl) +
                VarInt.sizeAscii(robotflags);
    }

    public void encodeValue(ByteBuffer bb) {
        VarInt.encode(bb, CURRENT_VERSION);
        VarInt.encodeAscii(bb, original);
        VarInt.encode(bb, status);
        VarInt.encodeAscii(bb, mimetype);
        VarInt.encode(bb, length);
        VarInt.encodeBytes(bb, base32.decode(digest));
        VarInt.encodeAscii(bb, file);
        VarInt.encode(bb, compressedoffset);
        VarInt.encodeAscii(bb, redirecturl);
        VarInt.encodeAscii(bb, robotflags);
    }

    public byte[] encodeValue() {
        ByteBuffer bb = ByteBuffer.allocate(sizeValue());
        encodeValue(bb);
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
        out.append(digest).append(' ');
        out.append(redirecturl).append(' ');
        out.append(robotflags).append(' ');
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
        capture.digest = fields[5];
        capture.redirecturl = fields[6];

        if (fields.length >= 11) { // 11 fields: CDX N b a m s k r M S V g
            capture.robotflags = fields[7];
            capture.length = fields[8].equals("-") ? 0 : Long.parseLong(fields[8]);
            capture.compressedoffset = Long.parseLong(fields[9]);
            capture.file = fields[10];
        } else if (fields.length == 10) { // 10 fields:  CDX N b a m s k r M V g
            capture.robotflags = fields[7];
            capture.compressedoffset = Long.parseLong(fields[8]);
            capture.file = fields[9];
        } else { // 9 fields: CDX N b a m s k r V g
            capture.robotflags = "-";
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

    /**
     * Gets the value of a field by name. We support several names as pywb and wayback-cdx-server use different names.
     *
     * @throws IllegalArgumentException for unknown fields
     */
    public Object get(String field) {
        switch (field) {
            case "urlkey":
                return urlkey;
            case "timestamp":
                return timestamp;
            case "url":
            case "original":
                return original;
            case "mime":
            case "mimetype":
                return mimetype;
            case "statuscode":
            case "status":
                return status;
            case "digest":
                return digest;
            case "redirecturl":
            case "redirect":
                return redirecturl;
            case "robotflags":
                return robotflags;
            case "length":
                return length;
            case "offset":
                return compressedoffset;
            case "filename":
                return file;
            default:
                throw new IllegalArgumentException("no such capture field: " + field);
        }
    }
}
