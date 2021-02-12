package outbackcdx;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CaptureTest {
    @Test
    public void testRecordsCanBeEncodedAndDecoded() {
        Capture src = dummyRecord();

        byte[] key = src.encodeKey();
        byte[] value = src.encodeValue();

        assertEquals(3, value[0]);
        assertEquals(8, value[1]);
        assertEquals('o', value[2]);
        assertEquals('r', value[3]);

        Capture dst = new Capture(key, value);
        assertFieldsEqual(src, dst);

        assertEquals(src.date(), dst.date());
        assertEquals(src.date().getTime(), 1388579640000L);
    }

    @Test
    public void testCdx9() {
        Capture src = Capture.fromCdxLine("- 19870102030405 http://example.org/ text/html 200 M5ORM4XQ5QCEZEDRNZRGSWXPCOGUVASI - 100 test.warc.gz", new UrlCanonicalizer());
        Capture dst = new Capture(src.encodeKey(), src.encodeValue());
        assertEquals(-1, src.length);
        assertEquals(100, src.compressedoffset);
        assertFieldsEqual(src, dst);
        assertEquals("org,example)/ 19870102030405 http://example.org/ text/html 200 M5ORM4XQ5QCEZEDRNZRGSWXPCOGUVASI - - - 100 test.warc.gz - - -", dst.toString());
    }

    @Test
    public void testV4Encoding() {
        Capture src = dummyRecord();
        byte[] key = src.encodeKey(4);
        byte[] value = src.encodeValue(4);
        Capture dst = new Capture(key, value);
        assertFieldsEqual(src, dst);
    }

    @Test
    public void testWbPostDataExtraction() {
        UrlCanonicalizer canonicalizer = new UrlCanonicalizer();

        // simple url with no parameters
        Capture cap = Capture.fromCdxLine("com,test)/append?__wb_post_data=dGVzdAo= 20200528143535 https://test.com/append application/json 202 2WC5VZGPEJIVA6BQPKMISFH7ISBVWYUQ - - 467 4846509 test.warc.gz", canonicalizer);
        assertEquals("com,test)/append?__wb_post_data=dGVzdAo=", cap.urlkey);

        // url with parameters
        cap = Capture.fromCdxLine("com,test)/append?x=1&__wb_post_data=dGVzdAo= 20200528143535 https://test.com/append?x=1 application/json 202 2WC5VZGPEJIVA6BQPKMISFH7ISBVWYUQ - - 467 4846509 test.warc.gz", canonicalizer);
        assertEquals("com,test)/append?x=1&__wb_post_data=dGVzdAo=", cap.urlkey);
    }

    @Test
    public void testRange() {
        assertEquals("bytes=1234-13578", dummyRecord().get("range"));
    }

    static void assertFieldsEqual(Capture src, Capture dst) {
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
        assertEquals(src.robotflags, dst.robotflags);
    }

    static Capture dummyRecord() {
        Capture src = new Capture();
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
        src.robotflags = "AFIGX";
        return src;
    }

}
