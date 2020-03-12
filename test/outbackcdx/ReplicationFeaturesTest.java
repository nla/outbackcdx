package outbackcdx;

import org.junit.*;
import org.junit.rules.TemporaryFolder;

import outbackcdx.NanoHTTPD.Response.Status;
import outbackcdx.auth.Permit;

import java.io.*;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

import static org.junit.Assert.assertEquals;
import static outbackcdx.NanoHTTPD.Method.*;
import static outbackcdx.NanoHTTPD.Response.Status.OK;


public class ReplicationFeaturesTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Webapp webapp;

    private DataStore manager;

    @Before
    public void setUp() throws IOException {
        File root = folder.newFolder();
        manager = new DataStore(root, 256, null, Long.MAX_VALUE, null);
        webapp = new Webapp(manager, false, Collections.emptyMap(), null, Collections.emptyMap());
    }

    @After
    public void tearDown() {
    }

    // tests for replication features:
    // ensure that write urls are disabled on secondary
    // ensure that we can retrieve a sequenceNumber on secondary
    // ensure that we can delete WALs on primary
    @Test
    public void testReadOnly() throws Exception {
        FeatureFlags.setSecondaryMode(true);
        // make a request to a write-able url
        // it should 401.
        POST("/test", "- 20050614070159 http://nla.gov.au/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n- 20030614070159 http://example.com/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - - - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n", Status.FORBIDDEN);
        FeatureFlags.setSecondaryMode(false);
    }

    @Test
    public void testSequenceNumber() throws Exception {
        FeatureFlags.setSecondaryMode(false);
        // post some CDX
        POST("/testa", "- 20050614070159 http://nla.gov.au/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n- 20030614070159 http://example.com/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - - - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n", OK);
        // get the sequenceNumber, should be 1
        String output = GET("/testa/sequence", OK);
        assertEquals("2", output);
        FeatureFlags.setSecondaryMode(false);
    }

    /*@Test
    public void testDeleteWals() throws Exception {
        FeatureFlags.setSecondaryMode(false);
        // post some CDX
        POST("/testb", "- 20050614070159 http://nla.gov.au/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n- 20030614070159 http://example.com/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - - - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n", OK);
        // check that we get items back from the iterator
        String output = GET("/testb/changes", OK, "since", "0");
        // assert output != {}
        assertNotEquals("{}", output);
        Index index = manager.getIndex("testb");
	index.db.flushWal(true);
        // make a request to delete WAL
        POST("/testb/truncate_replication", String.valueOf(index.db.getLatestSequenceNumber()), OK);
        // check that we get back no items from the iterator
        output = GET("/testb/changes", OK, "since", "0");
        assertEquals("{}", output);
        FeatureFlags.setSecondaryMode(false);
    }*/

    private String GET(String url, NanoHTTPD.Response.Status expectedStatus) throws Exception {
        ReplicationFeaturesTest.DummySession session = new ReplicationFeaturesTest.DummySession(GET, url);
	NanoHTTPD.Response response = webapp.handle(new Web.NRequest(session, Permit.full(), ""));
        assertEquals(expectedStatus, response.getStatus());
        return slurp(response);
    }

    private String GET(String url, NanoHTTPD.Response.Status expectedStatus, String... parmKeysAndValues) throws Exception {
        ReplicationFeaturesTest.DummySession session = new ReplicationFeaturesTest.DummySession(GET, url);
        for (int i = 0; i < parmKeysAndValues.length; i += 2) {
	    session.parm(parmKeysAndValues[i], parmKeysAndValues[i + 1]);
	}
	NanoHTTPD.Response response = webapp.handle(new Web.NRequest(session, Permit.full(), ""));
        assertEquals(expectedStatus, response.getStatus());
        return slurp(response);
    }

    private String DELETE(String url, NanoHTTPD.Response.Status expectedStatus) throws Exception {
        ReplicationFeaturesTest.DummySession session = new ReplicationFeaturesTest.DummySession(DELETE, url);
        NanoHTTPD.Response response = webapp.handle(new Web.NRequest(session, Permit.full(), ""));
        assertEquals(expectedStatus, response.getStatus());
        return slurp(response);
    }

    private String POST(String url, String data, NanoHTTPD.Response.Status expectedStatus) throws Exception {
        ReplicationFeaturesTest.DummySession session = new ReplicationFeaturesTest.DummySession(POST, url);
        session.data(data);
        NanoHTTPD.Response response = webapp.handle(new Web.NRequest(session, Permit.full(), ""));
        assertEquals(expectedStatus, response.getStatus());
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

        public ReplicationFeaturesTest.DummySession data(String data) {
            stream = new ByteArrayInputStream(data.getBytes(Charset.forName("UTF-8")));
            return this;
        }

        public ReplicationFeaturesTest.DummySession parm(String key, String value) {
            parms.put(key, value);
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
