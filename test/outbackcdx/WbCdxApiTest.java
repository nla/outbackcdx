package outbackcdx;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class WbCdxApiTest {
    @Test
    public void hostFromSurt() {
        assertEquals("org,example", Index.hostFromSurt("org,example)/foo/bar"));
        assertEquals("org,example", Index.hostFromSurt("org,example"));
    }

    @Test
    public void queryDefaultShouldExpandPrefixWildcards() {
        Map<String,String> params = new HashMap<>();
        params.put("url", "http://example.org/*");
        Query query = new Query(params);
        query.expandWildcards();
        assertEquals(Query.MatchType.PREFIX, query.matchType);
        assertEquals("http://example.org/", query.url);
    }

    @Test
    public void queryDefaultShouldExpandDomainWildcards() {
        Map<String,String> params = new HashMap<>();
        params.put("url", "*.example.org");
        Query query = new Query(params);
        query.expandWildcards();
        assertEquals(Query.MatchType.DOMAIN, query.matchType);
        assertEquals("example.org", query.url);
    }

    @Test
    public void queryExactMatchShouldNotExpandWildcards() {
        Map<String,String> params = new HashMap<>();
        params.put("url", "http://example.org/*");
        params.put("matchType", "exact");
        Query query = new Query(params);
        query.expandWildcards();
        assertEquals(Query.MatchType.EXACT, query.matchType);
        assertEquals("http://example.org/*", query.url);
    }
}