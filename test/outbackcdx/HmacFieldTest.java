package outbackcdx;

import org.junit.Test;

import static org.junit.Assert.*;

public class HmacFieldTest {
    @Test
    public void hmacsha256() {
        HmacField field = new HmacField("Hmacsha256", "$filename $offset $length",
                "$filename?hmac=$hmac_hex", "testkey", 3600);
        Capture capture = new Capture();
        capture.file = "example.warc.gz";
        capture.compressedoffset = 400;
        capture.length = 200;
        assertEquals("example.warc.gz?hmac=9e27a71a89b33a0e66a13288140ca26427fd2689a230881159935f0268c3c1f0", field.get(capture));
    }

    @Test
    public void hmacmd5() {
        HmacField field = new HmacField("md5", "$filename $offset $length $secret_key",
                "$filename?hmac=$hmac_hex", "testkey", 3600);
        Capture capture = new Capture();
        capture.file = "example.warc.gz";
        capture.compressedoffset = 400;
        capture.length = 200;
        assertEquals("example.warc.gz?hmac=1894419ebacc473d63f4f2d8088229b2", field.get(capture));
    }
}