package outbackcdx;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class WbCdxApiTest {
    @Test
    public void hostFromSurt() throws Exception {
        assertEquals("org,example", WbCdxApi.hostFromSurt("org,example)/foo/bar"));
        assertEquals("org,example", WbCdxApi.hostFromSurt("org,example"));
    }
}