package outbackcdx;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.rocksdb.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class IndexTest {

    private static Index index;
    private static RocksDB db;
    private static ColumnFamilyHandle defaultCf, aliasCf;
    private static RocksMemEnv env;

    @BeforeClass
    public static void setUp() throws RocksDBException, IOException {
        RocksDB.loadLibrary();
        env = new RocksMemEnv(Env.getDefault());
        try (Options options = new Options()
                .setCreateIfMissing(true)
                .setEnv(env)) {
            db = RocksDB.open(options, Paths.get("test").toAbsolutePath().toString());
            defaultCf = db.getDefaultColumnFamily();
            aliasCf = db.createColumnFamily(new ColumnFamilyDescriptor("alias".getBytes(StandardCharsets.UTF_8)));
            index = new Index("test", db, defaultCf, aliasCf, null);
        }
    }

    @AfterClass
    public static void tearDown() {
        aliasCf.close();
        defaultCf.close();
        db.close();
        env.close();
    }

    private static List<Capture> list(CloseableIterator<Capture> iterator) {
        List<Capture> captures = new ArrayList<>();
        iterator.forEachRemaining(captures::add);
        iterator.close();
        return captures;
    }

    @Test
    public void testClosest() throws IOException {
        try (Index.Batch batch = index.beginUpdate()) {
            batch.putCapture(Capture.fromCdxLine("- 20050101000000 http://closest.org/ text/html 200 - - 0 w1", index.canonicalizer));
            batch.putCapture(Capture.fromCdxLine("- 20060101000000 http://closest.org/ text/html 200 - - 0 w2", index.canonicalizer));
            batch.putCapture(Capture.fromCdxLine("- 20060201000000 http://closest.org/ text/html 200 - - 0 w2", index.canonicalizer));
            batch.putCapture(Capture.fromCdxLine("- 20070101000000 http://closest.org/ text/html 200 - - 0 w3", index.canonicalizer));
            batch.commit();
        }

        List<Capture> results = list(index.closestQuery("org,closest)/", 20060129000000L, null));
        assertEquals(20060201000000L, results.get(0).timestamp);
        assertEquals(20060101000000L, results.get(1).timestamp);
        assertEquals(20070101000000L, results.get(2).timestamp);
        assertEquals(20050101000000L, results.get(3).timestamp);
    }

    @Test
    public void testPostData() throws IOException {
        try (Index.Batch batch = index.beginUpdate()) {
            batch.putCapture(Capture.fromCdxLine("org,post)/?__wb_method=post&__wb_post_data=dGVzdAo= 20200528143307 http://post.org/ text/html 200 - - 0 w1", index.canonicalizer));
            batch.putCapture(Capture.fromCdxLine("org,post)/?__wb_method=post&__wb_post_data=dGVzdDIK 20200528143307 http://post.org/ text/html 200 - - 0 w1", index.canonicalizer));
            batch.commit();
        }

        List<Capture> results = list(index.closestQuery("org,post)/?__wb_method=post&__wb_post_data=dgvzdao=", 20200528143307L, null));
        assertEquals("org,post)/?__wb_method=post&__wb_post_data=dgvzdao=", results.get(0).urlkey);
    }

    @Test
    public void testDelete() throws IOException {
        try (Index.Batch batch = index.beginUpdate()) {
            batch.putCapture(Capture.fromCdxLine("- 20050101000000 http://a.org/ text/html 200 - - 0 w1", index.canonicalizer));
            batch.putCapture(Capture.fromCdxLine("- 20060101000000 http://a.org/ text/html 200 - - 0 w2", index.canonicalizer));
            batch.putCapture(Capture.fromCdxLine("- 20070101000000 http://a.org/ text/html 200 - - 0 w3", index.canonicalizer));
            batch.commit();
        }

        {
            List<Capture> results = list(index.query("org,a)/", null));
            assertEquals(3, results.size());
            assertEquals(20050101000000L, results.get(0).timestamp);
            assertEquals(20060101000000L, results.get(1).timestamp);
            assertEquals(20070101000000L, results.get(2).timestamp);
        }

        try (Index.Batch batch = index.beginUpdate()) {
            batch.deleteCapture(Capture.fromCdxLine("- 20060101000000 http://a.org/ text/html 200 - - 0 w2", index.canonicalizer));
            batch.commit();
        }

        {
            List<Capture> results = list(index.query("org,a)/", null));
            assertEquals(2, results.size());
            assertEquals(20050101000000L, results.get(0).timestamp);
            assertEquals(20070101000000L, results.get(1).timestamp);
        }
    }

    @Test
    public void testForwardAndReverse() throws IOException {
        try (Index.Batch batch = index.beginUpdate()) {
            batch.putCapture(Capture.fromCdxLine("- 20050101000000 http://a.org/ text/html 200 - - 0 w1", index.canonicalizer));
            batch.putCapture(Capture.fromCdxLine("- 20060101000000 http://a.org/ text/html 200 - - 0 w2", index.canonicalizer));
            batch.putCapture(Capture.fromCdxLine("- 20070101000000 http://a.org/ text/html 200 - - 0 w3", index.canonicalizer));
            batch.putCapture(Capture.fromCdxLine("- 19960101000000 http://b.org/ text/html 200 - - 0 w3", index.canonicalizer));
            batch.putCapture(Capture.fromCdxLine("- 19960101000000 http://c.org/ text/html 200 - - 0 w3", index.canonicalizer));
            batch.commit();
        }

        {
            List<Capture> results = list(index.reverseQuery("org,a)/", null));
            assertEquals(20070101000000L, results.get(0).timestamp);
            assertEquals(20060101000000L, results.get(1).timestamp);
            assertEquals(20050101000000L, results.get(2).timestamp);
        }

        {
            List<Capture> results = list(index.reverseQuery("org,b)/", null));
            assertEquals(1, results.size());
            assertEquals(19960101000000L, results.get(0).timestamp);
        }


        {
            List<Capture> results = list(index.query("org,a)/", null));
            assertEquals(20050101000000L, results.get(0).timestamp);
            assertEquals(20060101000000L, results.get(1).timestamp);
            assertEquals(20070101000000L, results.get(2).timestamp);
        }


    }

    @Test
    public void testFromAndTo() throws IOException {
        try (Index.Batch batch = index.beginUpdate()) {
            batch.putCapture(Capture.fromCdxLine("- 20050101000000 http://fromto.org/ text/html 200 - - 0 w1", index.canonicalizer));
            batch.putCapture(Capture.fromCdxLine("- 20060101000000 http://fromto.org/ text/html 200 - - 0 w2", index.canonicalizer));
            batch.putCapture(Capture.fromCdxLine("- 20070101000000 http://fromto.org/ text/html 200 - - 0 w3", index.canonicalizer));
            batch.putCapture(Capture.fromCdxLine("- 20080101000000 http://fromto.org/ text/html 200 - - 0 w3", index.canonicalizer));
            batch.putCapture(Capture.fromCdxLine("- 20090101000000 http://fromto.org/ text/html 200 - - 0 w3", index.canonicalizer));
            batch.commit();
        }

        {
            List<Capture> results = list(index.query("org,fromto)/", 20060000000000l, 20080000000000l, null));
            assertEquals(20060101000000L, results.get(0).timestamp);
            assertEquals(20070101000000L, results.get(1).timestamp);
        }

        {
            List<Capture> results = list(index.reverseQuery("org,fromto)/", 20060000000000l, 20080000000000l, null));
            assertEquals(20070101000000L, results.get(0).timestamp);
            assertEquals(20060101000000L, results.get(1).timestamp);
        }
    }

    @Test
    public void indexV4ShouldPreserveDistinctRecordsWithTheSameUrlAndDate() throws IOException {
        int oldVersion = FeatureFlags.indexVersion();
        FeatureFlags.setIndexVersion(4);
        try {
            try (Index.Batch batch = index.beginUpdate()) {
                batch.putCapture(Capture.fromCdxLine("- 20050101000000 http://v4.org/ text/html 200 - - 0 w1", index.canonicalizer));
                batch.putCapture(Capture.fromCdxLine("- 20050101000000 http://v4.org/ text/html 200 - - 10 w1", index.canonicalizer));
                batch.putCapture(Capture.fromCdxLine("- 20050101000000 http://v4.org/ text/html 200 - - 0 w2", index.canonicalizer));
                batch.putCapture(Capture.fromCdxLine("- 20050101000000 http://v4.org/ text/html 200 - - 0 w2", index.canonicalizer));
                batch.commit();
            }

            List<Capture> results = list(index.query("org,v4)/", null));
            assertEquals(3, results.size());
        } finally {
            FeatureFlags.setIndexVersion(oldVersion);
        }
    }

    @Test
    public void testUpgradeInPlace() throws IOException, RocksDBException {
        int initialVersion = FeatureFlags.indexVersion();
        try {


            // First create some records in the old format
            FeatureFlags.setIndexVersion(3);
            try (Index.Batch batch = index.beginUpdate()) {
                batch.putCapture(Capture.fromCdxLine("- 20050101000000 http://example.org/ text/html 200 - - 0 w1", index.canonicalizer));
                batch.putCapture(Capture.fromCdxLine("- 20050102000000 http://example.org/ text/html 200 - - 10 w1", index.canonicalizer));
                batch.putCapture(Capture.fromCdxLine("- 20050103000000 http://example.org/ text/html 200 - - 10 w1", index.canonicalizer));
                batch.commit();
            }

            {
                List<Capture> results = list(index.query("org,example)/", null));
                assertEquals(3, results.size());
            }

            // Now upgrade the index
            FeatureFlags.setIndexVersion(5);
            index.upgrade();

            {
                List<Capture> results = list(index.query("org,example)/", null));
                assertEquals(3, results.size());
            }

        } finally {
            FeatureFlags.setIndexVersion(initialVersion);
        }

        // Now upgrade the index
    }
}