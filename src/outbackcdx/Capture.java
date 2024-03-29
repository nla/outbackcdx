package outbackcdx;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.cbor.CBORGenerator;
import com.fasterxml.jackson.dataformat.cbor.CBORParser;
import org.apache.commons.codec.binary.Base32;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

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
 * value is a schema version number to allow fields to be added or removed in later versions. Version 5 values use CBOR
 * encoding to allow storing arbitrary CDXJ fields.
 */
public class Capture {
    private static final Logger log = Logger.getLogger(Capture.class.getName());
    static final DateTimeFormatter arcTimeFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    static final Base32 base32 = new Base32();

    public String urlkey;
    public long timestamp;
    public String original = "-";
    public String mimetype = "-";
    public int status = -1;
    public String digest = "-";
    public long length = -1;
    public String file = "-";
    public long compressedoffset = -1;
    public String redirecturl = "-";
    public String robotflags = "-";

    // Additional properties for CDX14
    public long originalLength = -1;
    public long originalCompressedoffset = -1;
    public String originalFile = "-";
    Map<String,Object> extra;

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
            case 5:
                return encodeKeyV4(urlkey, timestamp, file, compressedoffset);
            default:
                throw new IllegalArgumentException("unsupported version: " + version);
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
            case 5:
                decodeValueV5(bb);
                break;
            default:
                throw new IllegalArgumentException("CDX encoding is too new (v" + version + ") only versions up to v5 are supported");
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
        digest = base32Encode(VarInt.decodeBytes(bb));
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
        digest = base32Encode(VarInt.decodeBytes(bb));
        redirecturl = VarInt.decodeAscii(bb);
        robotflags = VarInt.decodeAscii(bb);
        originalLength = VarInt.decode(bb);
        originalFile = VarInt.decodeAscii(bb);
        originalCompressedoffset = VarInt.decode(bb);
    }

    public void decodeValueV5(ByteBuffer bb) {
        ByteArrayInputStream stream = new ByteArrayInputStream(bb.array(), bb.position(), bb.limit());
        try (CBORParser parser = Json.CBOR_FACTORY.createParser(stream)) {
            original = parser.nextTextValue();
            status = parser.nextIntValue(-1);
            mimetype = parser.nextTextValue();
            length = parser.nextLongValue(-1);
            parser.nextToken();
            digest = base32Encode(parser.getBinaryValue());
            redirecturl = parser.nextTextValue();
            robotflags = parser.nextTextValue();
            originalLength = parser.nextLongValue(-1);
            originalFile = parser.nextTextValue();
            originalCompressedoffset = parser.nextLongValue(-1);
            if (parser.nextToken() == JsonToken.START_OBJECT) {
                extra = Json.CBOR_MAPPER.readValue(parser, new TypeReference<Map<String, Object>>() {});
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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


    public byte[] encodeValue(int version) {
        switch (version) {
            case 3:
                ensureNoExtraFields();
                return encodeValueV3();
            case 4:
                ensureNoExtraFields();
                return encodeValueV4();
            case 5:
                return encodeValueV5();
            default:
                throw new IllegalArgumentException("Unsupported version " + version);
        }
    }

    private static final Set<String> INFERRABLE_EXTRA_FIELDS = new HashSet<>(Arrays.asList("method", "requestBody"));

    private void ensureNoExtraFields() {
        if (extra != null && !extra.isEmpty() && !INFERRABLE_EXTRA_FIELDS.containsAll(extra.keySet())) {
            throw new IllegalStateException("Can't encode capture with extra (CDXJ) fields in index version < 5");
        }
    }

    private byte[] encodeValueV3() {
        ByteBuffer bb = ByteBuffer.allocate(sizeValueV3());
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
        return bb.array();
    }

    private byte[] encodeValueV4() {
        ByteBuffer bb = ByteBuffer.allocate(sizeValueV4());
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
        return bb.array();
    }

    private byte[] encodeValueV5() {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(5); // version 5
        try (CBORGenerator generator = Json.CBOR_FACTORY.createGenerator(stream)) {
            generator.writeString(original);
            generator.writeNumber(status);
            generator.writeString(mimetype);
            generator.writeNumber(length);
            generator.writeBinary(base32.decode(digest));
            generator.writeString(redirecturl);
            generator.writeString(robotflags);
            generator.writeNumber(originalLength);
            generator.writeString(originalFile);
            generator.writeNumber(originalCompressedoffset);
            if (extra != null) {
                Json.CBOR_MAPPER.writeValue(generator, extra);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return stream.toByteArray();
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
        out.append(timestamp).append(" ");
        out.append(original).append(" ");
        out.append(mimetype).append(" ");
        out.append(status).append(" ");
        out.append(digest).append(" ");
        out.append(redirecturl).append(" ");
        out.append(robotflags).append(" ");
        out.append((length == -1) ? "-" : length).append(" ");
        out.append(compressedoffset).append(" ");
        out.append(file);

        if (FeatureFlags.indexVersion() >= 3) {
            out.append(" ");

            if (originalLength > 0) {
                out.append(originalLength).append(" ");
            } else {
                out.append("-").append(" ");
            }

            if (originalCompressedoffset > 0) {
                out.append(originalCompressedoffset).append(" ");
            } else {
                out.append("-").append(" ");
            }

            out.append(originalFile);
        }

        return out.toString();
    }

    public static Capture fromCdxLine(String line, UrlCanonicalizer canonicalizer) {
        String[] fields = line.split(" ");
        if (fields.length > 2 && fields[2].startsWith("{")) {
            return fromCdxjLine(line, canonicalizer);
        }

        Capture capture = new Capture();
        capture.timestamp = parseCdxTimestamp(fields[1]);
        capture.original = fields[2];
        capture.inferMethodAndRequestBodyFromOldUrlKey(fields[0], canonicalizer);
        capture.urlkey = capture.generateUrlKey(canonicalizer);
        capture.mimetype = fields[3];
        capture.status = fields[4].equals("-") ? 0 : Integer.parseInt(fields[4]);
        capture.digest = fields[5];
        capture.redirecturl = fields[6];

        // remove the digest scheme, if applicable
        if(capture.digest.contains(":")) {
            capture.digest = capture.digest.split(":")[1];
        }

        if (fields.length >= 11) { // 11 fields: CDX N b a m s k r M S V g
            capture.robotflags = fields[7];
            capture.length = fields[8].equals("-") ? -1 : Long.parseLong(fields[8]);
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

    private static Capture fromCdxjLine(String line, UrlCanonicalizer canonicalizer) {
        String[] fixedFields = line.split(" ", 3);
        Capture capture = new Capture();
        capture.timestamp = parseCdxTimestamp(fixedFields[1]);
        Map<String, Object> json;
        try {
            json = Json.JSON_MAPPER.readValue(fixedFields[2], new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON in CDXJ line: " + line, e);
        }
        for (Map.Entry<String, Object> entry: json.entrySet()) {
            capture.put(entry.getKey(), entry.getValue());
        }
        if (capture.original == null) {
            throw new IllegalArgumentException("Missing 'url' field in CDXJ line: " + line);
        }
        capture.inferMethodAndRequestBodyFromOldUrlKey(fixedFields[0], canonicalizer);
        capture.urlkey = capture.generateUrlKey(canonicalizer);
        return capture;
    }

    private String getExtraString(String field) {
        if (extra != null) {
            Object value = extra.get(field);
            if (value instanceof String) {
                return (String) value;
            }
        }
        return null;
    }

    private String[] extractQueryParams(String url) {
        int queryIndex = url.indexOf('?');
        if (queryIndex == -1) {
            return new String[0];
        }
        String query = url.substring(queryIndex + 1);
        return query.split("&");
    }

    /**
     * Returns the strings that are in a but not in b. a and b most both be sorted.
     */
    private List<String> diffParams(String[] a, String[] b) {
        List<String> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.length && j < b.length) {
            int cmp = a[i].compareTo(b[j]);
            if (cmp < 0) {
                result.add(a[i]);
                i++;
            } else if (cmp > 0) {
                j++;
            } else {
                i++;
                j++;
            }
        }
        while (i < a.length) {
            result.add(a[i]);
            i++;
        }
        return result;

    }

    /**
     * Attempts to infer the method and requestBody extra fields by comparing an old urlkey against the original url.
     * <p>
     * When run with the --post-append option, webrecorder/cdxj-indexer will include the request method and encoded
     * version of the request body as query parameters in the url key. In CDXJ output mode it will usually also
     * add extra "method" and "requestBody" fields for these values. However, in CDX11 output mode there no extra
     * fields. Older versions of cdxj-indexer in CDXJ mode also didn't populate the extra fields.
     * <p>
     * We can determine the fields from request body because they won't appear in the original url query string.
     * <p>
     * This method does nothing if the extra fields are already populated.
     */
    private void inferMethodAndRequestBodyFromOldUrlKey(String oldUrlKey, UrlCanonicalizer canonicalizer) {
        if (oldUrlKey == null) return;
        if (!oldUrlKey.contains("__wb_method=")) return;
        if (extra != null) {
            if (extra.containsKey("method")) return;
            if (extra.containsKey("requestBody")) return;
        }

        // if the old urlkey contains __wb_method but we don't have method and requestBody,
        // then we try to extract them from the urlkey by looking for query parameters that appear
        // in the old urlkey but aren't present in the original url field.
        String[] oldParams = extractQueryParams(oldUrlKey);
        String newUrlKey = canonicalizer.surtCanonicalize(original);
        String[] newParams = extractQueryParams(newUrlKey);
        List<String> extraParams = diffParams(oldParams, newParams);
        StringBuilder builder = new StringBuilder();
        for (String param: extraParams) {
            if (param.startsWith("__wb_method=")) {
                put("method", param.substring("__wb_method=".length()).toUpperCase(Locale.ROOT));
            } else {
                // probably a request body parameter
                if (builder.length() > 0) {
                    builder.append("&");
                }
                builder.append(param);
            }
        }
        if (builder.length() > 0) {
            put("requestBody", builder.toString());
        }
    }

    private String generateUrlKey(UrlCanonicalizer canonicalizer) {
        String method = getExtraString("method");
        String requestBody = getExtraString("requestBody");
        String url;
        if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) {
            StringBuilder builder = new StringBuilder(original);
            builder.append(original.contains("?") ? "&" : "?");
            builder.append("__wb_method=").append(method);
            if (requestBody != null && !requestBody.isEmpty()) {
                builder.append("&").append(requestBody);
            }
            url = builder.toString();
        } else {
            url = original;
        }
        return canonicalizer.surtCanonicalize(url);
    }

    /**
     * Convert a 14 digit CDX timestamp into a 64 bit integer (long). If the supplied string is too short, 0 will be
     * appended to pad it out. If the supplied string is to long, an exception will be thrown.
     * @param cdxTimestamp The CDX timestamp to convert
     * @return A 64 bit integer representation of the supplied timestamp
     * @throws IllegalArgumentException If the supplied timestamp exceeds 14 characters.
     * @throws NumberFormatException If the supplied timestamp contains non-numeric characters.
     */
    private static long parseCdxTimestamp(String cdxTimestamp) {
        if (cdxTimestamp.length() < 14) {
            log.log(Level.WARNING, "Padding timestamp shorter then 14 chars: " + cdxTimestamp);
            cdxTimestamp = cdxTimestamp + PAD_TIMESTAMP.substring(cdxTimestamp.length());
        }
        if (cdxTimestamp.length() > 14) {
            throw new IllegalArgumentException("CDX timestamp longer than 14 chars. Not supported");
        }

        return Long.parseLong(cdxTimestamp);
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

    private static long coerceLong(Object value) {
        if (value == null) {
            return -1;
        } else if (value instanceof String) {
            return Long.parseLong((String)value);
        } else if (value instanceof Long) {
            return (Long) value;
        } else if (value instanceof Integer) {
            return (long) (Integer)value;
        } else {
            throw new IllegalArgumentException(value.getClass().getName());
        }
    }

    private static String coerceString(Object value) {
        if (value == null) {
            return "-";
        } else if (value instanceof String) {
            return (String)value;
        } else {
            throw new IllegalArgumentException(value.getClass().getName());
        }
    }

    public void put(String field, Object value) {
        try {
            switch (field) {
                case "urlkey":
                    urlkey = coerceString(value);
                    break;
                case "timestamp":
                    timestamp = coerceLong(value);
                    break;
                case "url":
                case "original":
                    original = coerceString(value);
                    break;
                case "mime":
                case "mimetype":
                    mimetype = coerceString(value);
                    break;
                case "statuscode":
                case "status":
                    status = (int) coerceLong(value);
                    break;
                case "digest":
                    digest = coerceString(value);
                    break;
                case "redirecturl":
                case "redirect":
                    redirecturl = coerceString(value);
                    break;
                case "robotflags":
                    robotflags = coerceString(value);
                    break;
                case "length":
                    length = coerceLong(value);
                    break;
                case "offset":
                    compressedoffset = coerceLong(value);
                    break;
                case "filename":
                    file = coerceString(value);
                    break;
                case "originalLength":
                    originalLength = coerceLong(value);
                    break;
                case "originalOffset":
                    originalCompressedoffset = coerceLong(value);
                    break;
                case "originalFilename":
                    originalFile = coerceString(value);
                    break;
                default:
                    if (extra == null) {
                        extra = new HashMap<>();
                    }
                    extra.put(field, value);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected a number in field " + field, e);
        }
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
                return (length == -1) ? null : length;
            case "offset":
                return compressedoffset;
            case "filename":
                return file;
            case "originalLength":
                return (originalLength != -1) ? originalLength : null;
            case "originalOffset":
                return (originalCompressedoffset != -1) ? originalCompressedoffset : null;
            case "originalFilename":
                return originalFile;
            case "range":
                if (length == -1) {
                    return "bytes=" + compressedoffset + "-";
                } else {
                    return "bytes=" + compressedoffset + "-" + (compressedoffset + length - 1);
                }
            default:
                if (extra != null) {
                    return extra.get(field);
                }
                throw new IllegalArgumentException("no such capture field: " + field);
        }
    }

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder(data.length / 5 * 8);
        for (int i = 0; i < data.length; i += 5) {
            long buf = 0;

            // read 5 bytes
            if (i + 5 < data.length) {
                buf = (data[i] & 0xFFL) << (8 * 4) |
                        (data[i + 1] & 0xFFL) << (8 * 3) |
                        (data[i + 2] & 0xFFL) << (8 * 2) |
                        (data[i + 3] & 0xFFL) << 8 |
                        (data[i + 4] & 0xFFL);
            } else {
                for (int j = 0; j < 5; j++) {
                    buf <<= 8;
                    if (i + j < data.length) {
                        buf += data[i + j] & 0xff;
                    }
                }
            }

            // write 8 base32 characters
            for (int j = 0; j < 8; j++) {
                out.append(BASE32_ALPHABET.charAt((int)((buf >> ((7-j) * 5)) & 31)));
            }
        }
        return out.toString();
    }

    public boolean isSelfRedirect(UrlCanonicalizer canonicalizer) {
        if (redirecturl == null) return false;
        if (status < 300 || status >= 400) return false;
        if (redirecturl.equals(original)) return true;
        String redirectUrlKey = canonicalizer.surtCanonicalize(redirecturl);
        return urlkey.equals(redirectUrlKey);
    }
}
