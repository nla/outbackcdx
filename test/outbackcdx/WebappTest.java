package outbackcdx;

import org.apache.commons.lang.StringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import outbackcdx.UrlCanonicalizer.ConfigurationException;
import outbackcdx.auth.Permit;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static outbackcdx.Json.GSON;
import static outbackcdx.NanoHTTPD.Method.*;
import static outbackcdx.NanoHTTPD.Response.Status.BAD_REQUEST;
import static outbackcdx.NanoHTTPD.Response.Status.CREATED;
import static outbackcdx.NanoHTTPD.Response.Status.OK;

public class WebappTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Webapp webapp;

    @Before
    public void setUp() throws IOException, ConfigurationException {
        FeatureFlags.setExperimentalAccessControl(true);
        File root = folder.newFolder();
        String yaml =
                "rules:\n" +
                "- url_prefix: 'com,facebook)/pages_reaction_units/more'\n" +
                "  fuzzy_lookup:\n" +
                "  - page_id\n" +
                "  - cursor\n" +
                "";
        UrlCanonicalizer canon = new UrlCanonicalizer(new ByteArrayInputStream(yaml.getBytes("UTF-8")));

        DataStore manager = new DataStore(root, -1, null, Long.MAX_VALUE, canon);
        webapp = new Webapp(manager, false, Collections.emptyMap(), canon, Collections.emptyMap());
    }

    @After
    public void tearDown() {
        FeatureFlags.setExperimentalAccessControl(false);
    }

    @Test
    public void test() throws Exception {
        POST("/test", "- 20050614070159 http://nla.gov.au/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n- 20030614070159 http://example.com/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - - - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n");
        POST("/test", "- 20060614070159 http://nla.gov.au/ text/html 200 XKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - - - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n- 20040614070159 http://example.com/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - - - 337023 NLA-AU-CRAWL-000-20060614070144-00003-crawling016.archive.org\n");
        {
            String response = GET("/test", "url", "nla.gov.au");
            assertTrue(response.indexOf("au,gov,nla)/ 20050614070159") != -1);
            assertTrue(response.indexOf("example") == -1);
        }


        {
            String response = GET("/test", "q", "type:urlquery url:http%3A%2F%2Fnla.gov.au%2F");
            assertTrue(response.indexOf("20050614070159") != -1);
            assertTrue(response.indexOf("20060614070159") != -1);
        }

        POST("/test", "@alias http://example.com/ http://www.nla.gov.au/\n- 20100614070159 http://example.com/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - - - 337023 NLA-AU-CRAWL-000-20100614070144-00003-crawling016.archive.org\n");
        {
            String response = GET("/test", "q", "type:urlquery url:http%3A%2F%2Fnla.gov.au%2F");
            assertTrue(response.indexOf("20050614070159") != -1);
            assertTrue(response.indexOf("20060614070159") != -1);
            assertTrue(response.indexOf("20100614070159") != -1);
            assertTrue(response.indexOf("20030614070159") != -1);
        }


        {
            String response = GET("/test", "q", "type:urlquery url:http%3A%2F%2Fnla.gov.au%2F limit:2 offset:0");
            assertEquals(2, StringUtils.countMatches(response, "<result>"));
        }

        POST("/test", "- 20060614070159 http://nla.gov.au/bad-wolf text/html bad-wolf XKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - - - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n- 20040614070159 http://example.com/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - - - 337023 NLA-AU-CRAWL-000-20060614070144-00003-crawling016.archive.org\n", BAD_REQUEST);
        POST("/test", "- 20060614070159 http://nla.gov.au/bad-wolf text/html bad-wolf XKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - - - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n- 20040614070159 http://example.com/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - - - 337023 NLA-AU-CRAWL-000-20060614070144-00003-crawling016.archive.org\n", OK, "badLines", "skip");
    }

    @Test
    public void testFuzzyCanon() throws Exception {
        POST("/test", "- 20170819040336 https://www.facebook.com/pages_reaction_units/more/?page_id=115681848447769&cursor=%7B%22timeline_cursor%22%3A%22timeline_unit%3A1%3A00000000001397435609%3A04611686018427387904%3A09223372036854775661%3A04611686018427387904%22%2C%22timeline_section_cursor%22%3A%7B%7D%2C%22has_next_page%22%3Atrue%7D&surface=www_pages_home&unit_count=8&dpr=1&__user=100011276852661&__a=1&__dyn=5V4cjEzUGByK5A9VoWWOGi9Fxrz9EZz8-iWF3ozGFi9LFGA4XG7VKEKGwThEnUF7yWCHAxiESmqaxuqE88HyWDyuipi28gyEnGieKmjBXDmEgF3ebByqAAxaFSifDxCaVQibVojB-qjyVfh6u-exvz8Gicx2jCoO8hqwzxmmayrhbAyFUSibBDCyVF88GxrUCaC-Rx2Qh1Gcy8C6rld13xivQFfxqHu4olDh4dy8gyVUkCyFFFK5p8BaUhKHWG4ui9K8nVQGmV7Gh6BJq8G8WDDio8lfBGq9gZ4jyXV98-8t2eZKqaHyoO5pEpJaroK8Fp8HZ3aXAnjz4&__af=h0&__req=20&__be=-1&__pc=PHASED%3ADEFAULT&__rev=3239300&__spin_r=3239300&__spin_b=trunk&__spin_t=1503115250 application/x-javascript 200 FX2Z63TWFPQCNL6WBH5XJIHMS64NADL6 - - 35812 402977796 foo.warc.gz\n");
        {
            // look up a different url that canonicalizes to the same
            String response = GET("/test", "url", "https://www.facebook.com/pages_reaction_units/more/?page_id=115681848447769&cursor=%7B%22timeline_cursor%22%3A%22timeline_unit%3A1%3A00000000001397435609%3A04611686018427387904%3A09223372036854775661%3A04611686018427387904%22%2C%22timeline_section_cursor%22%3A%7B%7D%2C%22has_next_page%22%3Atrue%7D&surface=www_pages_home&unit_count=8&dpr=1&__user=100011276852661&__a=1&__dyn=5V4cjEzUGByK5A9VoWWOGi9Fxrz9EZz8-iWF3ozGFi9LFGA4XG7VKEKGwThEnUF7yWCHAxiESmqaxuqE88HyWDyuipi28gyEnGieKmjBXDmEgF3ebByqAAxaFSifDxCaVQibVojB-qjyVfh6u-exvz8Gicx2jCoO8hqwzxmmayrhbAyFUSibBDCyVF88GxrUCaC-Rx2Qh1Gcy8C6rld13xivQFfxqHu4olDh4dy8gyVUkCyFFFK5p8BaUhKHWG4ui9K8nVQGmV7Gh6BJq8G8WDDio8lfBGq9gZ4jyXV98-8t2eZKqaHyoO5pEpJaroK8Fp8HZ3aXAnjz4&__af=h0&__req=20&__be=-1&__pc=PHASED%3ADEFAULT&__rev=3239300&__spin_r=3239300&__spin_b=trunk&__spin_t=1503115250\n");
            assertEquals(response, "fuzzy:com,facebook)/pages_reaction_units/more?cursor={\"timeline_cursor\":\"timeline_unit:1:00000000001397435609:04611686018427387904:09223372036854775661:04611686018427387904\",\"timeline_section_cursor\":{},\"has_next_page\":true}&page_id=115681848447769 20170819040336 https://www.facebook.com/pages_reaction_units/more/?page_id=115681848447769&cursor=%7B%22timeline_cursor%22%3A%22timeline_unit%3A1%3A00000000001397435609%3A04611686018427387904%3A09223372036854775661%3A04611686018427387904%22%2C%22timeline_section_cursor%22%3A%7B%7D%2C%22has_next_page%22%3Atrue%7D&surface=www_pages_home&unit_count=8&dpr=1&__user=100011276852661&__a=1&__dyn=5V4cjEzUGByK5A9VoWWOGi9Fxrz9EZz8-iWF3ozGFi9LFGA4XG7VKEKGwThEnUF7yWCHAxiESmqaxuqE88HyWDyuipi28gyEnGieKmjBXDmEgF3ebByqAAxaFSifDxCaVQibVojB-qjyVfh6u-exvz8Gicx2jCoO8hqwzxmmayrhbAyFUSibBDCyVF88GxrUCaC-Rx2Qh1Gcy8C6rld13xivQFfxqHu4olDh4dy8gyVUkCyFFFK5p8BaUhKHWG4ui9K8nVQGmV7Gh6BJq8G8WDDio8lfBGq9gZ4jyXV98-8t2eZKqaHyoO5pEpJaroK8Fp8HZ3aXAnjz4&__af=h0&__req=20&__be=-1&__pc=PHASED%3ADEFAULT&__rev=3239300&__spin_r=3239300&__spin_b=trunk&__spin_t=1503115250 application/x-javascript 200 FX2Z63TWFPQCNL6WBH5XJIHMS64NADL6 - - 35812 402977796 foo.warc.gz\n");
            // look up a different url that canonicalizes to the same
            response = GET("/test", "url", "https://www.facebook.com/pages_reaction_units/more?random&junk=blahblah&page_id=115681848447769&somethingwhatever&cursor=%7B%22timeline_cursor%22%3A%22timeline_unit%3A1%3A00000000001397435609%3A04611686018427387904%3A09223372036854775661%3A04611686018427387904%22%2C%22timeline_section_cursor%22%3A%7B%7D%2C%22has_next_page%22%3Atrue%7D&surface=jifdopsajfiodspajfidsopajfidsoapfdjisaop&unit_count=3482743829&dpr=1&__user=4936279463271496327&__a=1&__dyn=fjdIOPFEUOPEJIOCPWEJIFOPJIOFEPJIFOWPEJIFOPUFHFHFH-iWF3ozGFi9LFGA4XG7VKEKGwThEnUF7yWCHAxiESmqaxuqE88HyWDyuipi28gyEnGieKmjBXDmEgF3ebByqAAxaFSifDxCaVQibVojB-qjyVfh6u-jifopejwqiofpjewiqfojpewiqofpjewiqofpewjqifeopwqjfiewoqpfewq-Rx2Qh1Gcy8C6rld13xivQFfxqHu4olDh4dy8gyVUkCyFFFK5p8BaUhKHWG4ui9K8nVQGmV7Gh6BJq8G8WDDio8lfBGq9gZ4jyXV98-8t2eZKqaHyoO5pEpJaroK8Fp8HZ3aXAnjz4&__af=h0&__req=20&__be=-1&__pc=PHASED%3ADEFAULT&__rev=333333333333333&__spin_r=4444444444444444&__spin_b=frunkles&__spin_t=6363636363663363636\n");
            assertEquals(response, "fuzzy:com,facebook)/pages_reaction_units/more?cursor={\"timeline_cursor\":\"timeline_unit:1:00000000001397435609:04611686018427387904:09223372036854775661:04611686018427387904\",\"timeline_section_cursor\":{},\"has_next_page\":true}&page_id=115681848447769 20170819040336 https://www.facebook.com/pages_reaction_units/more/?page_id=115681848447769&cursor=%7B%22timeline_cursor%22%3A%22timeline_unit%3A1%3A00000000001397435609%3A04611686018427387904%3A09223372036854775661%3A04611686018427387904%22%2C%22timeline_section_cursor%22%3A%7B%7D%2C%22has_next_page%22%3Atrue%7D&surface=www_pages_home&unit_count=8&dpr=1&__user=100011276852661&__a=1&__dyn=5V4cjEzUGByK5A9VoWWOGi9Fxrz9EZz8-iWF3ozGFi9LFGA4XG7VKEKGwThEnUF7yWCHAxiESmqaxuqE88HyWDyuipi28gyEnGieKmjBXDmEgF3ebByqAAxaFSifDxCaVQibVojB-qjyVfh6u-exvz8Gicx2jCoO8hqwzxmmayrhbAyFUSibBDCyVF88GxrUCaC-Rx2Qh1Gcy8C6rld13xivQFfxqHu4olDh4dy8gyVUkCyFFFK5p8BaUhKHWG4ui9K8nVQGmV7Gh6BJq8G8WDDio8lfBGq9gZ4jyXV98-8t2eZKqaHyoO5pEpJaroK8Fp8HZ3aXAnjz4&__af=h0&__req=20&__be=-1&__pc=PHASED%3ADEFAULT&__rev=3239300&__spin_r=3239300&__spin_b=trunk&__spin_t=1503115250 application/x-javascript 200 FX2Z63TWFPQCNL6WBH5XJIHMS64NADL6 - - 35812 402977796 foo.warc.gz\n");
        }

        // delete yet a different url that canonicalizes to the same
        POST("/test/delete", "- 20170819040336 https://www.facebook.com/pages_reaction_units/more/randomjunk1?randomjunk2&page_id=115681848447769&cursor=%7B%22timeline_cursor%22%3A%22timeline_unit%3A1%3A00000000001397435609%3A04611686018427387904%3A09223372036854775661%3A04611686018427387904%22%2C%22timeline_section_cursor%22%3A%7B%7D%2C%22has_next_page%22%3Atrue%7D application/x-javascript 200 FX2Z63TWFPQCNL6WBH5XJIHMS64NADL6 - - 35812 402977796 foo.warc.gz\n");
        {
            String response = GET("/test", "url", "https://www.facebook.com/pages_reaction_units/more/whatever/foo?random&junk=blahblah&page_id=115681848447769&somethingwhatever&cursor=%7B%22timeline_cursor%22%3A%22timeline_unit%3A1%3A00000000001397435609%3A04611686018427387904%3A09223372036854775661%3A04611686018427387904%22%2C%22timeline_section_cursor%22%3A%7B%7D%2C%22has_next_page%22%3Atrue%7D&surface=jifdopsajfiodspajfidsopajfidsoapfdjisaop&unit_count=3482743829&dpr=1&__user=4936279463271496327&__a=1&__dyn=fjdIOPFEUOPEJIOCPWEJIFOPJIOFEPJIFOWPEJIFOPUFHFHFH-iWF3ozGFi9LFGA4XG7VKEKGwThEnUF7yWCHAxiESmqaxuqE88HyWDyuipi28gyEnGieKmjBXDmEgF3ebByqAAxaFSifDxCaVQibVojB-qjyVfh6u-jifopejwqiofpjewiqfojpewiqofpjewiqofpewjqifeopwqjfiewoqpfewq-Rx2Qh1Gcy8C6rld13xivQFfxqHu4olDh4dy8gyVUkCyFFFK5p8BaUhKHWG4ui9K8nVQGmV7Gh6BJq8G8WDDio8lfBGq9gZ4jyXV98-8t2eZKqaHyoO5pEpJaroK8Fp8HZ3aXAnjz4&__af=h0&__req=20&__be=-1&__pc=PHASED%3ADEFAULT&__rev=333333333333333&__spin_r=4444444444444444&__spin_b=frunkles&__spin_t=6363636363663363636\n");
            assertEquals(response, "");
        }
    }

    @Test
    public void testDelete() throws Exception {
        POST("/test", "- 20050614070159 http://nla.gov.au/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n- 20030614070159 http://example.com/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - - - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n");
        POST("/test", "- 20060614070159 http://nla.gov.au/ text/html 200 XKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - - - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n- 20040614070159 http://example.com/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - - - 337023 NLA-AU-CRAWL-000-20060614070144-00003-crawling016.archive.org\n");

        {
            String response = GET("/test", "q", "type:urlquery url:http%3A%2F%2Fnla.gov.au%2F");
            assertTrue(response.contains("20050614070159"));
            assertTrue(response.contains("20060614070159"));
        }

        POST("/test/delete", "- 20060614070159 http://nla.gov.au/ text/html 200 XKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - - - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n- 20040614070159 http://example.com/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - - - 337023 NLA-AU-CRAWL-000-20060614070144-00003-crawling016.archive.org\n");

        {
            String response = GET("/test", "q", "type:urlquery url:http%3A%2F%2Fnla.gov.au%2F");
            assertTrue(response.contains("20050614070159"));
            assertTrue(!response.contains("20060614070159"));
        }
    }

    @Test
    public void testAccessPoint() throws Exception {
        POST("/testap",
                "- 20050614070159 http://a.ex.org/ text/html 200 - - 42 wrc\n" +
                "- 20030614070159 http://a.ex.org/ text/html 200 - - - - 42 wrc\n" +
                "- 20030614070159 http://b.ex.org/ text/html 200 - - - - 42 wrc\n");

        long publicPolicyId = createPolicy("Normal", "public", "staff");
        long staffPolicyId = createPolicy("Restricted", "staff");

        assertEquals(5, GSON.fromJson(GET("/testap/access/policies"), AccessPolicy[].class).length);

        createRule(publicPolicyId, "*");
        long ruleIdC = createRule(staffPolicyId, "*.c.ex.org");
        long ruleIdA = createRule(staffPolicyId, "*.a.ex.org");


        // default sort should be rule id
        {
            AccessRule[] actualRules = GSON.fromJson(GET("/testap/access/rules"), AccessRule[].class);
            assertEquals(3, actualRules.length);
            assertEquals(ruleIdC, (long) actualRules[1].id);
            assertEquals(ruleIdA, (long) actualRules[2].id);
        }

        // check sorting by SURT
        {
            AccessRule[] actualRules = GSON.fromJson(GET("/testap/access/rules", "sort", "surt"), AccessRule[].class);
            assertEquals(3, actualRules.length);
            assertEquals(ruleIdA, (long) actualRules[1].id);
            assertEquals(ruleIdC, (long) actualRules[2].id);
        }

        assertEquals(asList("http://a.ex.org/", "http://a.ex.org/", "http://b.ex.org/"),
                cdxUrls(GET("/testap", "url", "*.ex.org")));

        assertEquals(asList("http://a.ex.org/", "http://a.ex.org/", "http://b.ex.org/"),
                cdxUrls(GET("/testap/ap/staff", "url", "*.ex.org")));

        assertEquals(asList("http://b.ex.org/"),
                cdxUrls(GET("/testap/ap/public", "url", "*.ex.org")));

        //
        // try modifying a policy
        //

        AccessPolicy policy = GSON.fromJson(GET("/testap/access/policies/" + staffPolicyId), AccessPolicy.class);
        policy.accessPoints.remove("staff");
        POST("/testap/access/policies", GSON.toJson(policy));

        assertEquals(asList("http://b.ex.org/"),
                cdxUrls(GET("/testap/ap/staff", "url", "*.ex.org")));

        //
        // try modifying a rule
        //

        AccessRule rule = GSON.fromJson(GET("/testap/access/rules/" + ruleIdA), AccessRule.class);
        rule.urlPatterns.clear();
        rule.urlPatterns.add("*.b.ex.org");

        POST("/testap/access/rules", GSON.toJson(rule));

        assertEquals(asList("http://a.ex.org/", "http://a.ex.org/"),
                cdxUrls(GET("/testap/ap/public", "url", "*.ex.org")));

        //
        // try deleting a rule
        //

        DELETE("/testap/access/rules/" + ruleIdA);

        assertEquals(asList("http://a.ex.org/", "http://a.ex.org/", "http://b.ex.org/"),
                cdxUrls(GET("/testap/ap/public", "url", "*.ex.org")));


        //
        // invalid rules should be rejected
        //

        AccessRule badRule = new AccessRule();
        badRule.policyId = staffPolicyId;
        badRule.urlPatterns.add("*.example.org/with/a/path");
        POST("/testap/access/rules", GSON.toJson(badRule), BAD_REQUEST);

        AccessRule badRule2 = new AccessRule();
        badRule2.policyId = staffPolicyId;
        badRule2.urlPatterns.add("");
        POST("/testap/access/rules", GSON.toJson(badRule2), BAD_REQUEST);

        AccessRule badRule3 = new AccessRule();
        badRule3.policyId = staffPolicyId;
        POST("/testap/access/rules", GSON.toJson(badRule3), BAD_REQUEST);

    }

    List<String> cdxUrls(String cdx) {
        List<String> urls = new ArrayList<>();
        for (String line: cdx.trim().split("\n")) {
            urls.add(line.split(" ")[2]);
        }
        return urls;
    }

    private long createRule(long policyId, String... surts) throws Exception {
        AccessRule rule = new AccessRule();
        rule.policyId = policyId;
        rule.urlPatterns.addAll(asList(surts));
        String response = POST("/testap/access/rules", GSON.toJson(rule), CREATED);
        return GSON.fromJson(response, Id.class).id;
    }

    private long createPolicy(String name, String... accessPoints) throws Exception {
        AccessPolicy publicPolicy = new AccessPolicy();
        publicPolicy.name = name;
        publicPolicy.accessPoints.addAll(asList(accessPoints));

        String response = POST("/testap/access/policies", GSON.toJson(publicPolicy), CREATED);
        return GSON.fromJson(response, Id.class).id;
    }

    public static class Id {
        public long id;
    }

    private String POST(String url, String data) throws Exception {
        return POST(url, data, OK);
    }
    private String POST(String url, String data, NanoHTTPD.Response.Status expectedStatus, String... parmKeysAndValues) throws Exception {
        DummySession session = new DummySession(POST, url);
        session.data(data);
        for (int i = 0; i < parmKeysAndValues.length; i += 2) {
            session.parm(parmKeysAndValues[i], parmKeysAndValues[i + 1]);
        }
        NanoHTTPD.Response response = webapp.handle(new Web.NRequest(session, Permit.full(), ""));
        assertEquals(expectedStatus, response.getStatus());
        return slurp(response);
    }

    private String GET(String url, String... parmKeysAndValues) throws Exception {
        DummySession session = new DummySession(GET, url);
        for (int i = 0; i < parmKeysAndValues.length; i += 2) {
            session.parm(parmKeysAndValues[i], parmKeysAndValues[i + 1]);
        }
        NanoHTTPD.Response response = webapp.handle(new Web.NRequest(session, Permit.full(), ""));
        assertEquals(OK, response.getStatus());
        return slurp(response);
    }

    private String DELETE(String url) throws Exception {
        DummySession session = new DummySession(DELETE, url);
        NanoHTTPD.Response response = webapp.handle(new Web.NRequest(session, Permit.full(), ""));
        assertEquals(OK, response.getStatus());
        return slurp(response);
    }

    private String slurp(NanoHTTPD.Response response) throws IOException {
        NanoHTTPD.IStreamer streamer = response.getStreamer();
        if (streamer != null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            streamer.stream(out);
            return out.toString("UTF-8");
        }

        InputStream data = response.getData();
        if (data != null) {
            Scanner scanner = new Scanner(response.getData(), "UTF-8").useDelimiter("\\Z");
            if (scanner.hasNext()) {
                return scanner.next();
            }
        }

        return "";
    }

    private static class DummySession implements NanoHTTPD.IHTTPSession {
        private final NanoHTTPD.Method method;
        InputStream stream = new ByteArrayInputStream(new byte[0]);
        MultiMap<String, String> parms = new MultiMap<String, String>();
        String url;

        public DummySession(NanoHTTPD.Method method, String url) {
            this.url = url;
            this.method = method;
        }

        public DummySession data(String data) {
            stream = new ByteArrayInputStream(data.getBytes(Charset.forName("UTF-8")));
            return this;
        }

        public DummySession parm(String key, String value) {
            parms.add(key, value);
            return this;
        }

        @Override
        public void execute() throws IOException {
            // nothing
        }

        @Override
        public MultiMap<String, String> getParms() {
            return parms;
        }

        @Override
        public Map<String, String> getHeaders() {
            return Collections.emptyMap();
        }

        @Override
        public String getUri() {
            return url;
        }

        @Override
        public String getQueryParameterString() {
            return "";
        }

        @Override
        public NanoHTTPD.Method getMethod() {
            return method;
        }

        @Override
        public InputStream getInputStream() {
            return stream;
        }
    }
}
