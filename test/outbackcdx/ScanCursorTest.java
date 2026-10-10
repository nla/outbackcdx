package outbackcdx;

import com.moaduda.onggi.Keyspace;
import com.moaduda.onggi.Store;
import com.moaduda.onggi.WriteBatch;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ScanCursorTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static byte[] key(int i) {
        return String.format("k%08d", i).getBytes(US_ASCII);
    }

    @Test
    public void scansEveryRecordOnceAcrossReopens() throws IOException {
        int records = ScanCursor.REOPEN_INTERVAL * 3 + 7;
        try (Store store = Store.open(folder.newFolder().toPath())) {
            Keyspace keyspace = store.keyspace("default");
            WriteBatch batch = store.newBatch();
            for (int i = 0; i < records; i++) batch.put(keyspace, key(i), new byte[0]);
            store.write(batch);
            for (boolean deleting : new boolean[] {false, true}) {
                int seen = 0;
                try (ScanCursor it = new ScanCursor(keyspace)) {
                    for (; it.isValid(); it.next(), seen++) {
                        assertEquals(new String(key(seen), US_ASCII), new String(it.key(), US_ASCII));
                        // as an upgrade rewrites a record, unsynced
                        if (deleting) store.write(store.newBatch().delete(keyspace, it.key()), false);
                    }
                }
                assertEquals(records, seen);
            }
            try (ScanCursor it = new ScanCursor(keyspace)) {
                assertFalse("all deleted", it.isValid());
            }
        }
    }
}
