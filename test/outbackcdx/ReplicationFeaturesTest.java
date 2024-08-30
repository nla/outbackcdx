package outbackcdx;

import org.junit.*;
import org.junit.rules.TemporaryFolder;

import outbackcdx.Web.Status;
import outbackcdx.auth.NullAuthorizer;

import java.io.*;
import java.util.Collections;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static outbackcdx.Web.Method.*;
import static outbackcdx.Web.Status.OK;


public class ReplicationFeaturesTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Webapp webapp;

    private DataStore manager;

    @Before
    public void setUp() throws IOException {
        File root = folder.newFolder();
        manager = new DataStore(root, 256, null, Long.MAX_VALUE, null);
        webapp = new Webapp(manager, false, Collections.emptyMap(), null, Collections.emptyMap(), 10000, new QueryConfig());
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
    public void testChangePolling() throws Exception {
        POST("/src", "- 20050614070159 http://nla.gov.au/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n- 20030614070159 http://example.com/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - - - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n", OK);
        POST("/dest", "- 20050614070159 http://nla.gov.au/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n- 20030614070159 http://example.com/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - - - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n", OK);
        try (UWeb.UServer server = new UWeb.UServer("localhost", 0, "", webapp, new NullAuthorizer())) {
            server.start();
            ChangePollingThread pollingThread = new ChangePollingThread("http://localhost:" + server.port() + "/src", 1000, 10 * 1024 * 1024, manager);

            // Override the destination collection (by default it'll try to replicate src to itself)
            pollingThread.collection = "dest";
            pollingThread.index = manager.getIndex("dest", false);

            // Run an initial replication
            pollingThread.finalUrl = pollingThread.primaryReplicationUrl + "/changes?size=" + pollingThread.batchSize + "&since=" + 0;
            pollingThread.replicate();

            long initialSrcSeqNo = Long.parseLong(GET("/src/sequence", OK));

            // Now add a new record to the source collection
            POST("/src", "- 20050614070159 http://nla.gov.au/two text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n", OK);

            long updatedSrcSeqNo = Long.parseLong(GET("/src/sequence", OK));
            assertTrue(updatedSrcSeqNo > initialSrcSeqNo);

            // Replicate again
            pollingThread.finalUrl = pollingThread.primaryReplicationUrl + "/changes?size=" + pollingThread.batchSize + "&since=" + initialSrcSeqNo;
            pollingThread.replicate();

            // We should see the new record now appears in the destination collection too
            String response = GET("/dest", OK, "url", "http://nla.gov.au/two");
            assertTrue(response.contains("http://nla.gov.au/two"));
        }
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

    private String GET(String url, int expectedStatus) throws Exception {
        Web.Response response = webapp.handle(new DummyRequest(GET, url));
        assertEquals(expectedStatus, response.getStatus());
        return slurp(response);
    }

    private String GET(String url, int expectedStatus, String... parmKeysAndValues) throws Exception {
        DummyRequest request = new DummyRequest(GET, url);
        for (int i = 0; i < parmKeysAndValues.length; i += 2) {
	    request.parm(parmKeysAndValues[i], parmKeysAndValues[i + 1]);
	}
	Web.Response response = webapp.handle(request);
        assertEquals(expectedStatus, response.getStatus());
        return slurp(response);
    }

    private String DELETE(String url, int expectedStatus) throws Exception {
        Web.Response response = webapp.handle(new DummyRequest(DELETE, url));
        assertEquals(expectedStatus, response.getStatus());
        return slurp(response);
    }

    private String POST(String url, String data, int expectedStatus) throws Exception {
        Web.Response response = webapp.handle(new DummyRequest(POST, url, data));
        assertEquals(expectedStatus, response.getStatus());
        return slurp(response);
    }

    private String slurp(Web.Response response) throws IOException {
        Web.IStreamer streamer = response.getBodyWriter();
        if (streamer != null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            streamer.stream(out);
            return out.toString(UTF_8);
        }
        return "";
    }

}
