package tinycdxserver;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;


public class Record {
    private static int CURRENT_VERSION = 0;
    private static final DateTimeFormatter arcTimeFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");


    public String keyurl;
    public long timestamp;
    public String original;
    public String mimetype;
    public int status;
    public String digest;
    public long length;
    public String file;
    public long compressedoffset;
    public String redirecturl;

    public Record(byte[] key, byte[] value) {
        decodeKey(key);
        decodeValueV0(ByteBuffer.wrap(value));
    }

    private void decodeKey(byte[] key) {
        keyurl = new String(key, 0, key.length - 8, StandardCharsets.US_ASCII);
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
        return encodeKey(keyurl, timestamp);
    }

    public void decodeValue(ByteBuffer bb) {
        int version = (int)VarInt.decode(bb);
        switch (version) {
            case 0: decodeValueV0(bb); break;
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
    }

    public int sizeValue() {
        return VarInt.size(CURRENT_VERSION) +
                VarInt.sizeAscii(original) +
                VarInt.size(status) +
                VarInt.sizeAscii(mimetype) +
                VarInt.size(length) +
                VarInt.sizeAscii(digest) +
                VarInt.sizeAscii(file) +
                VarInt.size(compressedoffset) +
                VarInt.sizeAscii(redirecturl);
    }

    public void encodeValue(ByteBuffer bb) {
        VarInt.encode(bb, CURRENT_VERSION);
        VarInt.encodeAscii(bb, original);
        VarInt.encode(bb, status);
        VarInt.encodeAscii(bb, mimetype);
        VarInt.encode(bb, length);
        VarInt.encodeAscii(bb, digest);
        VarInt.encodeAscii(bb, file);
        VarInt.encode(bb, compressedoffset);
        VarInt.encodeAscii(bb, redirecturl);
    }

    public byte[] encodeValue() {
        ByteBuffer bb = ByteBuffer.allocate(sizeValue());
        encodeValue(bb);
        return bb.array();
    }
}
