package outbackcdx;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.rocksdb.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * Exercises Index#checkpoint. Uses a real on-disk RocksDB (not RocksMemEnv) so
 * the checkpoint API can actually link/copy SST files to a filesystem path.
 */
public class CheckpointTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void checkpointWhileDbIsOpenSucceedsAndIsReadable()
            throws RocksDBException, IOException {
        RocksDB.loadLibrary();
        Path dbPath = tmp.newFolder("db").toPath();
        Path checkpointPath = tmp.getRoot().toPath().resolve("snapshots").resolve("cp1");

        try (Options options = new Options().setCreateIfMissing(true);
             RocksDB db = RocksDB.open(options, dbPath.toString())) {
            ColumnFamilyHandle defaultCf = db.getDefaultColumnFamily();
            ColumnFamilyHandle aliasCf = db.createColumnFamily(
                    new ColumnFamilyDescriptor("alias".getBytes(StandardCharsets.UTF_8)));
            Index index = new Index("test", db, defaultCf, aliasCf, null);

            // Write at least one record so there's something to flush into an SST.
            try (Index.Batch batch = index.beginUpdate()) {
                batch.putCapture(Capture.fromCdxLine(
                        "- 20240101000000 http://example.org/ text/html 200 - - 0 w1",
                        index.canonicalizer));
                batch.commit();
            }
            db.flush(new FlushOptions().setWaitForFlush(true));

            // The whole point: take the checkpoint with the DB still open
            // (i.e. with the LOCK still held by this process).
            index.checkpoint(checkpointPath);

            // The checkpoint dir must contain the bits a fresh RocksDB needs to open.
            assertTrue("CURRENT should exist in checkpoint",
                    Files.exists(checkpointPath.resolve("CURRENT")));
            assertTrue("at least one MANIFEST should exist",
                    Files.list(checkpointPath)
                            .anyMatch(p -> p.getFileName().toString().startsWith("MANIFEST-")));

            aliasCf.close();
            defaultCf.close();
        }

        // Open the checkpoint as a standalone DB to prove it's a valid snapshot.
        // Must enumerate column families first since the source DB had a non-default
        // family ("alias") which RocksDB.open() alone won't open.
        try (Options listOpts = new Options()) {
            List<byte[]> cfNames = RocksDB.listColumnFamilies(listOpts, checkpointPath.toString());
            List<ColumnFamilyDescriptor> descriptors = cfNames.stream()
                    .map(ColumnFamilyDescriptor::new)
                    .collect(Collectors.toList());
            List<ColumnFamilyHandle> handles = new ArrayList<>();
            try (DBOptions dbOpts = new DBOptions().setCreateIfMissing(false);
                 RocksDB reopened = RocksDB.open(dbOpts, checkpointPath.toString(), descriptors, handles)) {
                assertEquals("checkpoint preserves alias column family",
                        2, handles.size());
            } finally {
                for (ColumnFamilyHandle h : handles) h.close();
            }
        }
    }

    @Test
    public void checkpointRefusesExistingTargetDirectory()
            throws RocksDBException, IOException {
        RocksDB.loadLibrary();
        Path dbPath = tmp.newFolder("db").toPath();
        Path checkpointPath = tmp.newFolder("already-here").toPath();

        try (Options options = new Options().setCreateIfMissing(true);
             RocksDB db = RocksDB.open(options, dbPath.toString())) {
            ColumnFamilyHandle defaultCf = db.getDefaultColumnFamily();
            ColumnFamilyHandle aliasCf = db.createColumnFamily(
                    new ColumnFamilyDescriptor("alias".getBytes(StandardCharsets.UTF_8)));
            Index index = new Index("test", db, defaultCf, aliasCf, null);

            assertThrows(IllegalArgumentException.class,
                    () -> index.checkpoint(checkpointPath));

            aliasCf.close();
            defaultCf.close();
        }
    }
}
