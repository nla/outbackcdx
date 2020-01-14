package outbackcdx;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WbCdxApiTest {
    @Test
    public void hostFromSurt() {
        assertEquals("org,example", Index.hostFromSurt("org,example)/foo/bar"));
        assertEquals("org,example", Index.hostFromSurt("org,example"));
    }

    @Test
    public void queryDefaultShouldExpandPrefixWildcards() {
        MultiMap<String,String> params = new MultiMap<>();
        params.put("url", "http://example.org/*");
        Query query = new Query(params, null);
        query.expandWildcards();
        assertEquals(Query.MatchType.PREFIX, query.matchType);
        assertEquals("http://example.org/", query.url);
    }

    @Test
    public void queryDefaultShouldExpandDomainWildcards() {
        MultiMap<String,String> params = new MultiMap<>();
        params.put("url", "*.example.org");
        Query query = new Query(params, null);
        query.expandWildcards();
        assertEquals(Query.MatchType.DOMAIN, query.matchType);
        assertEquals("example.org", query.url);
    }

    @Test
    public void queryExactMatchShouldNotExpandWildcards() {
        MultiMap<String,String> params = new MultiMap<>();
        params.put("url", "http://example.org/*");
        params.put("matchType", "exact");
        Query query = new Query(params, null);
        query.expandWildcards();
        assertEquals(Query.MatchType.EXACT, query.matchType);
        assertEquals("http://example.org/*", query.url);
    }
}