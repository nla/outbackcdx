package outbackcdx;

import org.apache.commons.codec.binary.Base32;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CaptureTest {
    @Test
    public void testRecordsCanBeEncodedAndDecoded() {
        Capture src = dummyRecord();

        byte[] key = src.encodeKey();
        byte[] value = src.encodeValue();

        assertEquals(1, value[0]);
        assertEquals(8, value[1]);
        assertEquals('o', value[2]);
        assertEquals('r', value[3]);

        Capture dst = new Capture(key, value);
        assertFieldsEqual(src, dst);

        assertEquals(src.date(), dst.date());
        assertEquals(src.date().getTime(), 1388579640000L);
    }

    static void assertFieldsEqual(Capture src, Capture dst) {
        assertEquals(src.compressedoffset, dst.compressedoffset);
        assertEquals(src.digestBase32(), dst.digestBase32());
        assertEquals(src.file, dst.file);
        assertEquals(src.length, dst.length);
        assertEquals(src.mimetype, dst.mimetype);
        assertEquals(src.original, dst.original);
        assertEquals(src.redirecturl, dst.redirecturl);
        assertEquals(src.status, dst.status);
        assertEquals(src.timestamp, dst.timestamp);
        assertEquals(src.urlkey, dst.urlkey);
    }

    static Capture dummyRecord() {
        Capture src = new Capture();
        src.compressedoffset = 1234;
        src.digest = new Base32().decode("2HQQSVUDLU4NZ67TN2KS3NG5AIVBVNFB");
        src.file = "file";
        src.length = 12345;
        src.mimetype = "mimetype";
        src.original = "original";
        src.redirecturl = "redirecturl";
        src.status = 200;
        src.timestamp = 20140101123400L;
        src.urlkey = "urlkey";
        return src;
    }
}
