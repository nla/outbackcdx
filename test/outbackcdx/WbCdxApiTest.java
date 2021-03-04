package outbackcdx;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Collections;

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

    @Test
    public void testCdxjOutputFormat() throws IOException {
        Query query = new Query(new MultiMap<>(), Collections.emptyList());
        StringWriter sw = new StringWriter();
        Capture capture = Capture.fromCdxLine("- 19870102030405 http://example.org/ text/html 200 M5ORM4XQ5QCEZEDRNZRGSWXPCOGUVASI - 100 test.warc.gz", new UrlCanonicalizer());
        new WbCdxApi.CdxjFormat(query, Collections.emptyMap(), sw).writeCapture(capture);
        assertEquals("org,example)/ 19870102030405 {\"url\":\"http://example.org/\",\"mime\":\"text/html\",\"status\":\"200\",\"digest\":\"M5ORM4XQ5QCEZEDRNZRGSWXPCOGUVASI\",\"offset\":\"100\",\"filename\":\"test.warc.gz\"}\n", sw.toString());
    }
}