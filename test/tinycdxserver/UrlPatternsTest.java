package tinycdxserver;

import org.junit.Test;

import static org.junit.Assert.*;

public class UrlPatternsTest {
    @Test
    public void testPattern() {
        assertEquals("au,gov,", UrlPatterns.toSsurtPrefix("*.gov.au"));
        assertEquals("au,gov,", UrlPatterns.toSsurtPrefix("*.GOV.AU"));
        assertEquals("com,example,//:http/foo/", UrlPatterns.toSsurtPrefix("http://EXAMPLE.com/foo/*"));
        assertEquals("com,example,//:http/foo/ ", UrlPatterns.toSsurtPrefix("http://example.com/foo/"));
    }

}