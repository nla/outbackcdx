package outbackcdx;

import com.moaduda.onggi.Options;
import com.moaduda.onggi.Store;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Exercises Index#checkpoint on a real on-disk store, so the checkpoint can
 * actually link tables to a filesystem path.
 */
public class CheckpointTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void checkpointWhileDbIsOpenSucceedsAndIsReadable() throws IOException {
        Path dbPath = tmp.newFolder("db").toPath();
        Path checkpointPath = tmp.getRoot().toPath().resolve("snapshots").resolve("cp1");

        long sequence;
        try (Store store = Store.open(dbPath)) {
            Index index = new Index("test", store, store.keyspace("default"), store.keyspace("alias"), null);

            // Write one record, flushed into a table, and leave another in the memtable.
            try (Index.Batch batch = index.beginUpdate()) {
                batch.putCapture(Capture.fromCdxLine(
                        "- 20240101000000 http://example.org/ text/html 200 - - 0 w1",
                        index.canonicalizer));
                batch.commit();
            }
            store.compact();
            try (Index.Batch batch = index.beginUpdate()) {
                batch.putCapture(Capture.fromCdxLine(
                        "- 20240101000001 http://example.org/two text/html 200 - - 0 w1",
                        index.canonicalizer));
                batch.commit();
            }
            sequence = store.sequence();

            // The whole point: take the checkpoint with the store still open
            // (i.e. with the LOCK still held by this process).
            index.checkpoint(checkpointPath);

            assertTrue("CURRENT should exist in checkpoint",
                    Files.exists(checkpointPath.resolve("CURRENT")));
            try (var files = Files.list(checkpointPath)) {
                assertTrue("at least one MANIFEST should exist",
                        files.anyMatch(p -> p.getFileName().toString().startsWith("MANIFEST-")));
            }
        }

        // Open the checkpoint as a standalone store to prove it's a valid snapshot.
        try (Store reopened = Store.open(checkpointPath, Options.defaults().withCreateIfMissing(false))) {
            assertEquals("checkpoint preserves the alias keyspace",
                    List.of("alias", "default"), reopened.keyspaceNames());
            assertEquals(sequence, reopened.sequence());
            Index index = new Index("test", reopened, reopened.keyspace("default"), reopened.keyspace("alias"), null);
            try (var captures = index.query("org,example)/two", null)) {
                assertTrue("checkpoint holds the unflushed record", captures.hasNext());
            }
        }
    }

    @Test
    public void checkpointRefusesExistingTargetDirectory() throws IOException {
        Path dbPath = tmp.newFolder("db").toPath();
        Path checkpointPath = tmp.newFolder("already-here").toPath();

        try (Store store = Store.open(dbPath)) {
            Index index = new Index("test", store, store.keyspace("default"), store.keyspace("alias"), null);
            assertThrows(IllegalArgumentException.class,
                    () -> index.checkpoint(checkpointPath));
        }
    }
}
