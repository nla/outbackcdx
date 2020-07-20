package outbackcdx;

import org.apache.commons.codec.binary.Base32;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Map;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

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
 * <p>Version 4 keys are extended to include the WARC filename and record offset and two NUL bytes. The first NUL used
 * to determine the length of filename (searching backwards from the end). The second is a flag indicating this is the
 * new key version.
 * <pre>
 *     +---------+------------------+-----|----------+-----+---------------+
 *     | urlkey  | 64-bit timestamp | NUL | filename | NUL | 64-bit offset |
 *     +---------+------------------+-----|----------+-----+---------------+
 * </pre>
 * <p>
 * The record's value consists of a static list fields packed using {@link outbackcdx.VarInt}.  The first field in the
 * value is a schema version number to allow fields to be added or removed in later versions.
 */
public class Capture {
    private static final Logger log = Logger.getLogger(Capture.class.getName());
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

    // Additional properties for CDX14
    public long originalLength = -1;
    public long originalCompressedoffset = -1;
    public String originalFile = "-";

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
        if (key.length > 8 && key[key.length - 9] == 0) {
            decodeKeyV4(key);
        } else {
            decodeKeyV0(key);
        }
    }

    private void decodeKeyV0(byte[] key) {
        urlkey = new String(key, 0, key.length - 8, US_ASCII);
        ByteBuffer keyBuf = ByteBuffer.wrap(key);
        timestamp = keyBuf.getLong(key.length - 8);
    }

    @SuppressWarnings("StatementWithEmptyBody")
    private void decodeKeyV4(byte[] key) {
        int i;
        for (i = key.length - 10; i >= 0 && key[i] != 0; i--);
        if (i <= 8) throw new IllegalArgumentException("bad key");
        ByteBuffer keyBuf = ByteBuffer.wrap(key);
        urlkey = new String(key, 0, i - 8, US_ASCII);
        timestamp = keyBuf.getLong(i - 8);
        file = new String(key, i + 1, key.length - i - 10);
        compressedoffset = keyBuf.getLong(key.length - 8);
    }

    public static byte[] encodeKeyV0(String keyurl, long timestamp) {
        byte[] urlBytes = keyurl.getBytes(US_ASCII);
        ByteBuffer bb = ByteBuffer.allocate(urlBytes.length + 8);
        bb.order(ByteOrder.BIG_ENDIAN);
        bb.put(urlBytes);
        bb.putLong(timestamp);
        return bb.array();
    }

    public static byte[] encodeKeyV4(String keyurl, long timestamp, String file, long offset) {
        byte[] urlBytes = keyurl.getBytes(US_ASCII);
        byte[] fileBytes = file.getBytes(UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(urlBytes.length + 8 + 1 + fileBytes.length + 1 + 8);
        bb.order(ByteOrder.BIG_ENDIAN);
        bb.put(urlBytes);
        bb.putLong(timestamp);
        bb.put((byte)0);
        bb.put(fileBytes);
        bb.put((byte)0);
        bb.putLong(offset);
        return bb.array();
    }

    public byte[] encodeKey() {
        return encodeKey(FeatureFlags.indexVersion());
    }

    public byte[] encodeKey(int version) {
        switch (version) {
            case 0:
            case 1:
            case 2:
            case 3:
                return encodeKeyV0(urlkey, timestamp);
            case 4:
                return encodeKeyV4(urlkey, timestamp, file, compressedoffset);
            default:
                throw new IllegalArgumentException("unsupported version: " + 4);
        }
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
            case 3:
                decodeValueV3(bb);
                break;
            case 4:
                decodeValueV4(bb);
                break;
            default:
                throw new IllegalArgumentException("CDX encoding is too new (v" + version + ") only versions up to v4 are supported");
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

    private void decodeValueV3(ByteBuffer bb) {
        decodeValueV2(bb);
        originalLength = VarInt.decode(bb);
        originalFile = VarInt.decodeAscii(bb);
        originalCompressedoffset = VarInt.decode(bb);
    }

    private void decodeValueV4(ByteBuffer bb) {
        original = VarInt.decodeAscii(bb);
        status = (int) VarInt.decode(bb);
        mimetype = VarInt.decodeAscii(bb);
        length = VarInt.decode(bb);
        digest = base32.encodeAsString(VarInt.decodeBytes(bb));
        redirecturl = VarInt.decodeAscii(bb);
        robotflags = VarInt.decodeAscii(bb);
        originalLength = VarInt.decode(bb);
        originalFile = VarInt.decodeAscii(bb);
        originalCompressedoffset = VarInt.decode(bb);
    }

    public int sizeValue() {
        return sizeValue(FeatureFlags.indexVersion());
    }

    public int sizeValue(int version) {
        switch (version) {
            case 3:
                return sizeValueV3();
            case 4:
                return sizeValueV4();
            default:
                throw new IllegalArgumentException("Unsupported version " + version);
        }
    }

    private int sizeValueV3() {
        return VarInt.size(3) +
                VarInt.sizeAscii(original) +
                VarInt.size(status) +
                VarInt.sizeAscii(mimetype) +
                VarInt.size(length) +
                VarInt.sizeBytes(base32.decode(digest)) +
                VarInt.sizeAscii(file) +
                VarInt.size(compressedoffset) +
                VarInt.sizeAscii(redirecturl) +
                VarInt.sizeAscii(robotflags) +
                VarInt.size(originalLength) +
                VarInt.sizeAscii(originalFile) +
                VarInt.size(originalCompressedoffset);
    }

    private int sizeValueV4() {
        return VarInt.size(4) +
                VarInt.sizeAscii(original) +
                VarInt.size(status) +
                VarInt.sizeAscii(mimetype) +
                VarInt.size(length) +
                VarInt.sizeBytes(base32.decode(digest)) +
                VarInt.sizeAscii(redirecturl) +
                VarInt.sizeAscii(robotflags) +
                VarInt.size(originalLength) +
                VarInt.sizeAscii(originalFile) +
                VarInt.size(originalCompressedoffset);
    }


    public void encodeValue(ByteBuffer bb) {
        encodeValue(bb, FeatureFlags.indexVersion());
    }

    private void encodeValue(ByteBuffer bb, int version) {
        switch (version) {
            case 3:
                encodeValueV3(bb);
                break;
            case 4:
                encodeValueV4(bb);
                break;
            default:
                throw new IllegalArgumentException("Unsupported version " + version);
        }
    }

    private void encodeValueV3(ByteBuffer bb) {
        VarInt.encode(bb, 3);
        VarInt.encodeAscii(bb, original);
        VarInt.encode(bb, status);
        VarInt.encodeAscii(bb, mimetype);
        VarInt.encode(bb, length);
        VarInt.encodeBytes(bb, base32.decode(digest));
        VarInt.encodeAscii(bb, file);
        VarInt.encode(bb, compressedoffset);
        VarInt.encodeAscii(bb, redirecturl);
        VarInt.encodeAscii(bb, robotflags);
        VarInt.encode(bb, originalLength);
        VarInt.encodeAscii(bb, originalFile);
        VarInt.encode(bb, originalCompressedoffset);
    }

    private void encodeValueV4(ByteBuffer bb) {
        VarInt.encode(bb, 4);
        VarInt.encodeAscii(bb, original);
        VarInt.encode(bb, status);
        VarInt.encodeAscii(bb, mimetype);
        VarInt.encode(bb, length);
        VarInt.encodeBytes(bb, base32.decode(digest));
        VarInt.encodeAscii(bb, redirecturl);
        VarInt.encodeAscii(bb, robotflags);
        VarInt.encode(bb, originalLength);
        VarInt.encodeAscii(bb, originalFile);
        VarInt.encode(bb, originalCompressedoffset);
    }

    public byte[] encodeValue(int version) {
        ByteBuffer bb = ByteBuffer.allocate(sizeValue(version));
        encodeValue(bb, version);
        return bb.array();
    }

    public byte[] encodeValue() {
        return encodeValue(FeatureFlags.indexVersion());
    }

    /**
     * Format as a CDX11 line, or CDX14, depending on what fields are present.
     */
    public String toString() {
        StringBuilder out = new StringBuilder();
        out.append(urlkey).append(" ");
        out.append(Long.toString(timestamp)).append(" ");
        out.append(original).append(" ");
        out.append(mimetype).append(" ");
        out.append(Integer.toString(status)).append(" ");
        out.append(digest).append(" ");
        out.append(redirecturl).append(" ");
        out.append(robotflags).append(" ");
        out.append(Long.toString(length)).append(" ");
        out.append(compressedoffset).append(" ");
        out.append(file);

        if (FeatureFlags.indexVersion() >= 3) {
            out.append(" ");

            if (originalLength > 0) {
                out.append(Long.toString(originalLength)).append(" ");
            } else {
                out.append("-").append(" ");
            }

            if (originalCompressedoffset > 0) {
                out.append(Long.toString(originalCompressedoffset)).append(" ");
            } else {
                out.append("-").append(" ");
            }

            out.append(originalFile);
        }

        return out.toString();
    }

    public static Capture fromCdxLine(String line, UrlCanonicalizer canonicalizer) {
        String[] fields = line.split(" ");
        Capture capture = new Capture();
        capture.timestamp = Long.parseLong(fields[1]);
        capture.original = fields[2];
        capture.urlkey = canonicalizer.surtCanonicalize(capture.original);
        capture.mimetype = fields[3];
        capture.status = fields[4].equals("-") ? 0 : Integer.parseInt(fields[4]);
        capture.digest = fields[5];
        capture.redirecturl = fields[6];

        if (fields.length >= 11) { // 11 fields: CDX N b a m s k r M S V g
            capture.robotflags = fields[7];
            capture.length = fields[8].equals("-") ? 0 : Long.parseLong(fields[8]);
            capture.compressedoffset = Long.parseLong(fields[9]);
            capture.file = fields[10];

            if (fields.length == 14 ) { // 14 fields: CDX N b a m s k r M S V g
                capture.originalLength = fields[11].equals("-") ? 0 : Long.parseLong(fields[11]);
                capture.originalCompressedoffset = fields[12].equals("-") ? 0 : Long.parseLong(fields[12]);
                capture.originalFile = fields[13];
            }
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

    static final String PAD_TIMESTAMP = "00000000000000"; // we expect 14 chars

    public static Date parseTimestamp(long timestamp) {
        String timestampstr = Long.toString(timestamp);
        if (timestampstr.length() < 14) {
            log.log(Level.WARNING, "Padding timestamp shorter then 14 chars: " + timestampstr);
            timestampstr = timestampstr + PAD_TIMESTAMP.substring(timestampstr.length());
        }
        return Date.from(LocalDateTime.parse(timestampstr, arcTimeFormat).toInstant(ZoneOffset.UTC));
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
            case "originalLength":
                return (originalLength != -1) ? originalLength : "-";
            case "originalOffset":
                return (originalCompressedoffset != -1) ? originalCompressedoffset : "-";
            case "originalFilename":
                return originalFile;
            case "range":
                return "bytes=" + compressedoffset + "-" + (compressedoffset + length - 1);
            default:
                throw new IllegalArgumentException("no such capture field: " + field);
        }
    }
}
