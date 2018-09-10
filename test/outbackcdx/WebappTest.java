package outbackcdx;

import org.apache.commons.lang.StringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import outbackcdx.auth.NullAuthorizer;

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
    public void setUp() throws IOException {
        FeatureFlags.setExperimentalAccessControl(true);
        File root = folder.newFolder();
        DataStore manager = new DataStore(root);
        webapp = new Webapp(manager, false, new NullAuthorizer(), Collections.emptyMap());
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
    private String POST(String url, String data, NanoHTTPD.Response.Status expectedStatus) throws Exception {
        DummySession session = new DummySession(POST, url);
        session.data(data);
        NanoHTTPD.Response response = webapp.handle(session);
        assertEquals(expectedStatus, response.getStatus());
        return slurp(response);
    }

    private String GET(String url, String... parmKeysAndValues) throws Exception {
        DummySession session = new DummySession(GET, url);
        for (int i = 0; i < parmKeysAndValues.length; i += 2) {
            session.parm(parmKeysAndValues[i], parmKeysAndValues[i + 1]);
        }
        NanoHTTPD.Response response = webapp.handle(session);
        assertEquals(OK, response.getStatus());
        return slurp(response);
    }

    private String DELETE(String url) throws Exception {
        DummySession session = new DummySession(DELETE, url);
        NanoHTTPD.Response response = webapp.handle(session);
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
        Map<String, String> parms = new HashMap<String, String>();
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
            parms.put(key, value);
            return this;
        }

        @Override
        public void execute() throws IOException {
            // nothing
        }

        @Override
        public Map<String, String> getParms() {
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
