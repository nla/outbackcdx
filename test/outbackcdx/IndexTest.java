package outbackcdx;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.rocksdb.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class IndexTest {

    private static Index index;
    private static RocksDB db;
    private static ColumnFamilyHandle defaultCf, aliasCf;
    private static RocksMemEnv env;

    @BeforeClass
    public static void setUp() throws RocksDBException {
        RocksDB.loadLibrary();
        env = new RocksMemEnv();
        try (Options options = new Options()
                .setCreateIfMissing(true)
                .setEnv(env)) {
            db = RocksDB.open(options, "test");
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

    @Test
    public void testClosest() throws IOException {
        try (Index.Batch batch = index.beginUpdate()) {
            batch.putCapture(Capture.fromCdxLine("- 20050101000000 http://closest.org/ text/html 200 - - 0 w1", index.canonicalizer));
            batch.putCapture(Capture.fromCdxLine("- 20060101000000 http://closest.org/ text/html 200 - - 0 w2", index.canonicalizer));
            batch.putCapture(Capture.fromCdxLine("- 20060201000000 http://closest.org/ text/html 200 - - 0 w2", index.canonicalizer));
            batch.putCapture(Capture.fromCdxLine("- 20070101000000 http://closest.org/ text/html 200 - - 0 w3", index.canonicalizer));
            batch.commit();
        }

        List<Capture> results = new ArrayList<>();
        index.closestQuery("org,closest)/", 20060129000000L, null).forEach(results::add);
        assertEquals(20060201000000L, results.get(0).timestamp);
        assertEquals(20060101000000L, results.get(1).timestamp);
        assertEquals(20070101000000L, results.get(2).timestamp);
        assertEquals(20050101000000L, results.get(3).timestamp);
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
            List<Capture> results = new ArrayList<>();
            index.query("org,a)/", null).forEach(results::add);
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
            List<Capture> results = new ArrayList<>();
            index.query("org,a)/", null).forEach(results::add);
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
            List<Capture> results = new ArrayList<>();
            index.reverseQuery("org,a)/", null).forEach(results::add);
            assertEquals(20070101000000L, results.get(0).timestamp);
            assertEquals(20060101000000L, results.get(1).timestamp);
            assertEquals(20050101000000L, results.get(2).timestamp);
        }

        {
            List<Capture> results = new ArrayList<>();
            index.reverseQuery("org,b)/", null).forEach(results::add);
            assertEquals(1, results.size());
            assertEquals(19960101000000L, results.get(0).timestamp);
        }


        {
            List<Capture> results = new ArrayList<>();
            index.query("org,a)/", null).forEach(results::add);
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
            List<Capture> results = new ArrayList<>();
            index.query("org,fromto)/", 20060000000000l, 20080000000000l, null).forEach(results::add);
            assertEquals(20060101000000L, results.get(0).timestamp);
            assertEquals(20070101000000L, results.get(1).timestamp);
        }

        {
            List<Capture> results = new ArrayList<>();
            index.reverseQuery("org,fromto)/", 20060000000000l, 20080000000000l, null).forEach(results::add);
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

            List<Capture> results = new ArrayList<>();
            index.query("org,v4)/", null).forEach(results::add);
            assertEquals(3, results.size());
        } finally {
            FeatureFlags.setIndexVersion(oldVersion);
        }
    }
}