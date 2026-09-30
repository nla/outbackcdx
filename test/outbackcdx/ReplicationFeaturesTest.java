package outbackcdx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import outbackcdx.Web.Status;
import outbackcdx.auth.NullAuthorizer;

import org.rocksdb.FlushOptions;

import java.io.*;
import java.util.Collections;
import java.util.OptionalLong;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static outbackcdx.Web.Method.*;
import static outbackcdx.Web.Status.OK;


public class ReplicationFeaturesTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final String TWO_RECORDS =
            "- 20050614070159 http://nla.gov.au/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n"
            + "- 20030614070159 http://example.com/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - - - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n";

    private Webapp webapp;

    private DataStore manager;

    @Before
    public void setUp() throws IOException {
        File root = folder.newFolder();
        manager = new DataStore(root, 256, null, Long.MAX_VALUE, null);
        webapp = new Webapp(manager, false, Collections.emptyMap(), null, Collections.emptyMap(), 10000, new QueryConfig(), null, null);
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

    /**
     * A cursor that has aged out of the WAL retention window must be refused,
     * not served from whatever WAL survives.
     *
     * This is the dangerous case. getUpdatesSince() does not fail for a purged
     * sequence: with later WALs still present it returns an iterator positioned
     * *after* the gap, so without this check the feed hands back post-gap
     * batches, the replica applies them and advances its cursor, and the
     * skipped records are lost with nothing logged anywhere.
     */
    @Test
    public void testFeedRefusesSequenceOlderThanRetention() throws Exception {
        File root = folder.newFolder();
        // Two-second WAL retention so archived WALs become purgeable in-test.
        // This test is necessarily timing-dependent: RocksDB enforces
        // WalTtlSeconds by file age and only purges from its obsolete-file
        // cleanup, which a flush triggers.
        try (DataStore store = new DataStore(root, 256, 2L, Long.MAX_VALUE, null)) {
            Webapp app = new Webapp(store, false, Collections.emptyMap(), null,
                    Collections.emptyMap(), 10000, new QueryConfig(), null, null);
            post(app, "/aged", TWO_RECORDS);
            Index index = store.getIndex("aged");
            long earlySequence = index.getLatestSequenceNumber();

            // Roll several WALs into the archive, age them past the TTL, then
            // write and flush again to trigger the purge -- the same sequence
            // of events as the production incident, where a checkpoint's flush
            // collected WALs that had expired days earlier.
            for (int i = 0; i < 3; i++) {
                post(app, "/aged", TWO_RECORDS);
                flushAll(index);
            }
            Thread.sleep(4000);
            post(app, "/aged", TWO_RECORDS);
            flushAll(index);

            OptionalLong oldest = index.getOldestAvailableSequenceNumber();
            assertTrue("expected the early WAL to be purged, oldest available is " + oldest,
                    oldest.isPresent() && oldest.getAsLong() > earlySequence);

            Web.Response refused = app.handle(withParams(new DummyRequest(GET, "/aged/changes"),
                    "since", String.valueOf(earlySequence)));
            assertEquals(Status.GONE, refused.getStatus());
            assertTrue(slurp(refused).contains("no longer available"));

            // since=0 is not exempt: the WAL no longer reaches the start of the
            // collection, so a replica cannot bootstrap completely from the feed.
            Web.Response fromScratch = app.handle(withParams(new DummyRequest(GET, "/aged/changes"),
                    "since", "0"));
            assertEquals(Status.GONE, fromScratch.getStatus());

            // And a sequence still covered by a retained WAL is served.
            Web.Response served = app.handle(withParams(new DummyRequest(GET, "/aged/changes"),
                    "since", String.valueOf(oldest.getAsLong())));
            assertEquals(OK, served.getStatus());
        }
    }

    /** Both column families must flush before their WAL can be archived. */
    private static void flushAll(Index index) throws Exception {
        try (FlushOptions options = new FlushOptions().setWaitForFlush(true)) {
            index.db.flush(options, java.util.Arrays.asList(index.defaultCF, index.aliasCF));
        }
    }

    /**
     * Being caught up must not be confused with having aged out. A cursor at
     * the tail is ahead of every retained sequence, so it answers empty rather
     * than 410.
     */
    @Test
    public void testCaughtUpCursorAnswers204NotGone() throws Exception {
        POST("/src", TWO_RECORDS, OK);
        long tail = Long.parseLong(GET("/src/sequence", OK)) + 1;
        Web.Response response = webapp.handle(withParams(new DummyRequest(GET, "/src/changes"),
                "since", String.valueOf(tail)));
        assertEquals(Status.NO_CONTENT, response.getStatus());
        assertEquals("", slurp(response));
    }

    /**
     * A replica whose cursor was reset to the tail of a collection with no
     * retained WAL is caught up, not aged out. It must get 204, since 410
     * would send an operator chasing a resync it does not need. This is the
     * state a recovered replica lands in.
     */
    @Test
    public void testTailCursorOnPurgedWalIsNotGone() throws Exception {
        File root = folder.newFolder();
        try (DataStore store = new DataStore(root, 256, 2L, Long.MAX_VALUE, null)) {
            Webapp app = new Webapp(store, false, Collections.emptyMap(), null,
                    Collections.emptyMap(), 10000, new QueryConfig(), null, null);
            post(app, "/tail", TWO_RECORDS);
            Index index = store.getIndex("tail");
            for (int i = 0; i < 3; i++) {
                post(app, "/tail", TWO_RECORDS);
                flushAll(index);
            }
            Thread.sleep(4000);
            post(app, "/tail", TWO_RECORDS);
            flushAll(index);

            long tail = index.getLatestSequenceNumber() + 1;
            Web.Response response = app.handle(withParams(new DummyRequest(GET, "/tail/changes"),
                    "since", String.valueOf(tail)));
            assertEquals(Status.NO_CONTENT, response.getStatus());
        }
    }

    /**
     * A fresh replica has nothing to derive a position from, so it asks for
     * everything and stores no sequence until a batch arrives.
     */
    @Test
    public void testEmptyCollectionBootstrapsFromZero() throws Exception {
        POST("/src", TWO_RECORDS, OK);
        POST("/dest", "", OK);
        try (UWeb.UServer server = new UWeb.UServer("localhost", 0, "", webapp, new NullAuthorizer())) {
            server.start();
            ChangePollingThread polling = pollingThread(server, "dest");
            assertEquals("0", polling.resolveSince());
            assertEquals(OptionalLong.empty(), polling.index.getReplicationSequence());
        }
    }

    /**
     * The documented recovery is to copy a collection from the primary and
     * restart. The copy carries the primary's data but none of its own
     * replication state, so the replica has to recognise that and derive its
     * position, or it asks for 0 and gets a 410 once the primary's WAL no longer
     * reaches the collection's first write.
     *
     * Posting identical records to both collections reproduces the copy: each
     * write consumes the same sequence numbers, so dest's latest sequence is
     * what a copy of src would carry.
     */
    @Test
    public void testCopiedCollectionAdoptsItsOwnSequence() throws Exception {
        POST("/src", TWO_RECORDS, OK);
        POST("/dest", TWO_RECORDS, OK);
        try (UWeb.UServer server = new UWeb.UServer("localhost", 0, "", webapp, new NullAuthorizer())) {
            server.start();
            ChangePollingThread polling = pollingThread(server, "dest");
            long copiedAt = polling.index.getLatestSequenceNumber();

            assertEquals(String.valueOf(copiedAt + 1), polling.resolveSince());
            // Stored, so the derivation happens once rather than on every poll.
            assertEquals(copiedAt + 1, storedCursor(polling));

            // The primary is idle and holds nothing past that point, so the
            // adopted position reads as caught up rather than as a gap.
            assertEquals(Status.NO_CONTENT, webapp.handle(withParams(
                    new DummyRequest(GET, "/src/changes"),
                    "since", String.valueOf(copiedAt + 1))).getStatus());
        }
    }

    /**
     * Writes the primary took after the copy was made must still arrive.
     */
    @Test
    public void testCopiedCollectionCatchesUpFromAdoptedSequence() throws Exception {
        POST("/src", TWO_RECORDS, OK);
        POST("/dest", TWO_RECORDS, OK);
        try (UWeb.UServer server = new UWeb.UServer("localhost", 0, "", webapp, new NullAuthorizer())) {
            server.start();
            ChangePollingThread polling = pollingThread(server, "dest");

            POST("/src", "- 20050614070159 http://nla.gov.au/after-copy text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n", OK);

            replicateSince(polling, Long.parseLong(polling.resolveSince()));
            assertTrue(GET("/dest", OK, "url", "http://nla.gov.au/after-copy")
                    .contains("http://nla.gov.au/after-copy"));
            assertEquals(Long.parseLong(GET("/src/sequence", OK)) + 1, storedCursor(polling));
        }
    }

    /**
     * A copy made before the primary's WAL rolled past it cannot be completed by
     * the feed: the records between what the copy holds and what the WAL retains
     * are gone. Adopting the copy's sequence must surface that as a 410 rather
     * than silently resuming from the WAL floor.
     */
    @Test
    public void testCopyOlderThanRetentionIsRefused() throws Exception {
        File root = folder.newFolder();
        try (DataStore store = new DataStore(root, 256, 2L, Long.MAX_VALUE, null)) {
            Webapp app = new Webapp(store, false, Collections.emptyMap(), null,
                    Collections.emptyMap(), 10000, new QueryConfig(), null, null);
            post(app, "/aged", TWO_RECORDS);
            post(app, "/copy", TWO_RECORDS);
            Index primary = store.getIndex("aged");
            long copiedAt = store.getIndex("copy").getLatestSequenceNumber();

            for (int i = 0; i < 3; i++) {
                post(app, "/aged", TWO_RECORDS);
                flushAll(primary);
            }
            Thread.sleep(4000);
            post(app, "/aged", TWO_RECORDS);
            flushAll(primary);

            OptionalLong oldest = primary.getOldestAvailableSequenceNumber();
            assertTrue("expected the copy to fall outside the retained WAL",
                    oldest.isPresent() && oldest.getAsLong() > copiedAt + 1);

            try (UWeb.UServer server = new UWeb.UServer("localhost", 0, "", app, new NullAuthorizer())) {
                server.start();
                ChangePollingThread polling = new ChangePollingThread(
                        "http://localhost:" + server.port() + "/aged", 1000, 10 * 1024 * 1024, store);
                polling.collection = "copy";
                polling.index = store.getIndex("copy", false);

                String since = polling.resolveSince();
                assertEquals(String.valueOf(copiedAt + 1), since);
                assertEquals(Status.GONE, app.handle(withParams(
                        new DummyRequest(GET, "/aged/changes"), "since", since)).getStatus());

                // The poll leaves the sequence where it is, so the operator sees
                // the position the copy was taken at rather than a moved cursor.
                polling.finalUrl = polling.primaryReplicationUrl
                        + "/changes?size=" + polling.batchSize + "&since=" + since;
                polling.replicate();
                assertEquals(copiedAt + 1, storedCursor(polling));
            }
        }
    }

    /**
     * A replica that lost only its stored sequence still holds data, but that
     * data is in its own sequence space, which runs far ahead of the primary's.
     * Adopting it would request a sequence past the primary's tail, which reads
     * as caught up -- a silent stall. Refuse instead.
     */
    @Test
    public void testSequenceBeyondPrimaryIsRefused() throws Exception {
        POST("/src", TWO_RECORDS, OK);
        POST("/dest", TWO_RECORDS, OK);
        POST("/dest", "- 20050614070159 http://nla.gov.au/local text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n", OK);
        try (UWeb.UServer server = new UWeb.UServer("localhost", 0, "", webapp, new NullAuthorizer())) {
            server.start();
            ChangePollingThread polling = pollingThread(server, "dest");
            assertTrue(polling.index.getLatestSequenceNumber()
                    > Long.parseLong(GET("/src/sequence", OK)));

            assertNull("must not poll on a sequence it cannot justify", polling.resolveSince());
            assertEquals(OptionalLong.empty(), polling.index.getReplicationSequence());
        }
    }

    /**
     * A primary that is briefly down must not be mistaken for one that has
     * nothing to offer: deriving a position needs its sequence to check against,
     * so without it the poll waits rather than falling back to 0.
     */
    @Test
    public void testAdoptionWaitsWhenPrimaryUnreachable() throws Exception {
        POST("/dest", TWO_RECORDS, OK);
        ChangePollingThread polling = new ChangePollingThread(
                "http://localhost:1/dest", 1000, 10 * 1024 * 1024, manager);
        assertNull(polling.resolveSince());
        assertEquals(OptionalLong.empty(), polling.index.getReplicationSequence());
    }

    private static DummyRequest withParams(DummyRequest request, String... keysAndValues) {
        for (int i = 0; i < keysAndValues.length; i += 2) {
            request.parm(keysAndValues[i], keysAndValues[i + 1]);
        }
        return request;
    }

    private String post(Webapp app, String url, String data) throws Exception {
        Web.Response response = app.handle(new DummyRequest(POST, url, data));
        assertEquals(OK, response.getStatus());
        return slurp(response);
    }

    /**
     * The cursor must land on the primary's next unwritten sequence, so that a
     * caught-up replica transfers nothing. Storing the applied batch's own
     * sequence number left it one batch behind, and because /changes is
     * inclusive of `since` the replica then re-fetched and re-applied that same
     * batch on every poll indefinitely.
     *
     * The exact-value assertion is also what catches reading count() after the
     * marker put: that yields primary latest + 2.
     */
    @Test
    public void testCursorTracksPrimaryTail() throws Exception {
        POST("/src", TWO_RECORDS, OK);
        POST("/dest", "", OK);
        try (UWeb.UServer server = new UWeb.UServer("localhost", 0, "", webapp, new NullAuthorizer())) {
            server.start();
            ChangePollingThread polling = pollingThread(server, "dest");

            replicateSince(polling, 0);
            long primaryLatest = Long.parseLong(GET("/src/sequence", OK));
            assertEquals(primaryLatest + 1, storedCursor(polling));

            // Nothing new upstream: the poll must move no data and no cursor,
            // and must not raise -- the feed answers empty rather than 500.
            replicateSince(polling, storedCursor(polling));
            assertEquals(primaryLatest + 1, storedCursor(polling));
        }
    }

    /**
     * A cursor one sequence too high still finds a following batch of two or
     * more entries, because getUpdatesSince() returns the batch containing the
     * requested sequence. A batch of exactly one entry is skipped outright, so
     * only this case exposes that error -- as missing records rather than as a
     * failure at the point of the mistake.
     */
    @Test
    public void testSingleEntryBatchIsNotSkipped() throws Exception {
        POST("/src", TWO_RECORDS, OK);
        POST("/dest", "", OK);
        try (UWeb.UServer server = new UWeb.UServer("localhost", 0, "", webapp, new NullAuthorizer())) {
            server.start();
            ChangePollingThread polling = pollingThread(server, "dest");
            replicateSince(polling, 0);

            long before = Long.parseLong(GET("/src/sequence", OK));
            POST("/src", "- 20050614070159 http://nla.gov.au/single text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n", OK);
            long after = Long.parseLong(GET("/src/sequence", OK));
            assertEquals("expected a single-entry batch", 1, after - before);

            replicateSince(polling, storedCursor(polling));
            assertTrue(GET("/dest", OK, "url", "http://nla.gov.au/single")
                    .contains("http://nla.gov.au/single"));
            assertEquals(after + 1, storedCursor(polling));
        }
    }

    /**
     * Replication lag is (primary latest + 1) - (replica cursor), and both halves
     * have to be readable from /stats so a monitoring client needs no extra
     * request. The cursor is reported only where one exists.
     */
    @Test
    public void testStatsCarriesReplicationPosition() throws Exception {
        POST("/src", TWO_RECORDS, OK);
        POST("/dest", "", OK);
        try (UWeb.UServer server = new UWeb.UServer("localhost", 0, "", webapp, new NullAuthorizer())) {
            server.start();
            ChangePollingThread polling = pollingThread(server, "dest");
            replicateSince(polling, 0);

            String primary = GET("/src/stats", OK);
            String replica = GET("/dest/stats", OK);

            assertTrue("a primary has no cursor to report",
                    !primary.contains("nextReplicationSequence"));
            assertEquals(Long.parseLong(GET("/src/sequence", OK)),
                    jsonLong(primary, "latestSequenceNumber"));
            assertEquals(storedCursor(polling), jsonLong(replica, "nextReplicationSequence"));
        }
    }

    private static long jsonLong(String json, String field) throws IOException {
        JsonNode value = new ObjectMapper().readTree(json).get(field);
        assertNotNull(field + " missing from " + json, value);
        return value.asLong();
    }

    private ChangePollingThread pollingThread(UWeb.UServer server, String destination) throws IOException {
        ChangePollingThread polling = new ChangePollingThread(
                "http://localhost:" + server.port() + "/src", 1000, 10 * 1024 * 1024, manager);
        // By default it would replicate src to itself.
        polling.collection = destination;
        polling.index = manager.getIndex(destination, false);
        return polling;
    }

    private void replicateSince(ChangePollingThread polling, long since) throws Exception {
        polling.finalUrl = polling.primaryReplicationUrl
                + "/changes?size=" + polling.batchSize + "&since=" + since;
        polling.replicate();
    }

    private long storedCursor(ChangePollingThread polling) throws Exception {
        OptionalLong cursor = polling.index.getReplicationSequence();
        assertTrue("replication cursor was never stored", cursor.isPresent());
        return cursor.getAsLong();
    }

    /**
     * A collection whose WAL holds no batches yields an unpositioned
     * TransactionLogIterator, and getBatch() must not be called on one.
     *
     * Without the isValid() guard in ChangeFeedJsonStream this fails here as an
     * AssertionError from RocksDB's own assert(isValid()), because surefire runs
     * with assertions enabled. Production does not: there the assert is skipped,
     * getBatch() returns a WriteBatch whose native handle is 0, and
     * WriteBatch.data() kills the JVM with a SIGSEGV.
     */
    @Test
    public void testChangeFeedOnCollectionWithNoWrites() throws Exception {
        // Creates the collection without writing any records to it.
        POST("/nowrites", "", OK);
        Web.Response response = webapp.handle(withParams(new DummyRequest(GET, "/nowrites/changes"),
                "since", "0"));
        // Nothing exists rather than nothing survives, so 204 and not 410.
        assertEquals(Status.NO_CONTENT, response.getStatus());
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
        if (response == Web.Response.ALREADY_SENT) {
            response = request.streamedResponse();
        }
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
