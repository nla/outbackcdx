package tinycdxserver;

import org.junit.Test;

import static org.junit.Assert.*;

public class SSURTTest {
    @Test
    public void toSsurt() throws Exception {
        assertEquals("(org,example,www,):80:http:/foo/bar/baz.html", SSURT.fromUrl("http://www.example.org/foo/bar/baz.html"));
        assertEquals("(org,example,www,):8080:http:/", SSURT.fromUrl("http://www.example.org:8080/"));
        assertEquals("(org,example,www,):1443:https:/", SSURT.fromUrl("https://www.example.org:1443"));
        assertEquals("(org,example,www,):1443:https:user:pass/", SSURT.fromUrl("https://user:pass@www.example.org:1443/"));
        assertEquals("1.2.3.4:8080:https:/", SSURT.fromUrl("https://1.2.3.4:8080/"));
        assertEquals("(jp,xn--wgv71a,):80:http:/[%20F%C3%9CNKY%20]", SSURT.fromUrl(" http:/日本.jp:80//.././[ FÜNKY ] "));
        // TODO
        //assertEquals("[2001:db8:0:0:1:0:0:1]:8080:https:/", SSURT.fromUrl("https://[2001:db8::1:0:0:1]:8080/"));
    }

    @Test
    public void testPattern() {
        assertEquals("(au,gov,", SSURT.prefixFromPattern("*.gov.au"));
        assertEquals("(com,example,):80:http:/foo/", SSURT.prefixFromPattern("http://EXAMPLE.com/foo/*"));
        assertEquals("(com,example,):80:http:/foo/ ", SSURT.prefixFromPattern("http://example.com/foo/"));
    }

}