package tinycdxserver;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RecordTest {
    @Test
    public void testRecordsCanBeEncodedAndDecoded() {
        Record src = dummyRecord();

        byte[] key = src.encodeKey();
        byte[] value = src.encodeValue();

        assertEquals(1, value[0]);
        assertEquals(8, value[1]);
        assertEquals('o', value[2]);
        assertEquals('r', value[3]);

        Record dst = new Record(key, value);
        assertFieldsEqual(src, dst);
    }

    static void assertFieldsEqual(Record src, Record dst) {
        assertEquals(src.compressedoffset, dst.compressedoffset);
        assertEquals(src.digest, dst.digest);
        assertEquals(src.file, dst.file);
        assertEquals(src.length, dst.length);
        assertEquals(src.mimetype, dst.mimetype);
        assertEquals(src.original, dst.original);
        assertEquals(src.redirecturl, dst.redirecturl);
        assertEquals(src.status, dst.status);
        assertEquals(src.timestamp, dst.timestamp);
        assertEquals(src.urlkey, dst.urlkey);
    }

    static Record dummyRecord() {
        Record src = new Record();
        src.compressedoffset = 1234;
        src.digest = "2HQQSVUDLU4NZ67TN2KS3NG5AIVBVNFB";
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
