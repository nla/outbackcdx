package outbackcdx;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class WbCdxApiTest {
    @Test
    public void hostFromSurt() {
        assertEquals("org,example", WbCdxApi.hostFromSurt("org,example)/foo/bar"));
        assertEquals("org,example", WbCdxApi.hostFromSurt("org,example"));
    }

    @Test
    public void queryDefaultShouldExpandPrefixWildcards() {
        Map<String,String> params = new HashMap<>();
        params.put("url", "http://example.org/*");
        WbCdxApi.Query query = new WbCdxApi.Query(params);
        query.expandWildcards();
        assertEquals(WbCdxApi.MatchType.PREFIX, query.matchType);
        assertEquals("http://example.org/", query.url);
    }

    @Test
    public void queryDefaultShouldExpandDomainWildcards() {
        Map<String,String> params = new HashMap<>();
        params.put("url", "*.example.org");
        WbCdxApi.Query query = new WbCdxApi.Query(params);
        query.expandWildcards();
        assertEquals(WbCdxApi.MatchType.DOMAIN, query.matchType);
        assertEquals("example.org", query.url);
    }

    @Test
    public void queryExactMatchShouldNotExpandWildcards() {
        Map<String,String> params = new HashMap<>();
        params.put("url", "http://example.org/*");
        params.put("matchType", "exact");
        WbCdxApi.Query query = new WbCdxApi.Query(params);
        query.expandWildcards();
        assertEquals(WbCdxApi.MatchType.EXACT, query.matchType);
        assertEquals("http://example.org/*", query.url);
    }
}