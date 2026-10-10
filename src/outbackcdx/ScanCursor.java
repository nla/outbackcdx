package outbackcdx;

import com.moaduda.onggi.Cursor;
import com.moaduda.onggi.Keyspace;

import java.util.Arrays;

/**
 * A forward scan over a whole keyspace for long jobs such as an index upgrade or dump. An Onggi cursor reads a
 * snapshot and pins the files it needs, so this reopens it at the last key every {@link #REOPEN_INTERVAL} records,
 * as RocksDB's tailing iterator did. Records written behind the scan after it passes are not seen.
 */
class ScanCursor implements AutoCloseable {
    static final int REOPEN_INTERVAL = 16384;

    private final Keyspace keyspace;
    private Cursor cursor;
    private int sinceReopen;

    ScanCursor(Keyspace keyspace) {
        this.keyspace = keyspace;
        this.cursor = keyspace.cursor();
        cursor.first();
    }

    boolean isValid() {
        return cursor.isValid();
    }

    byte[] key() {
        return cursor.key();
    }

    byte[] value() {
        return cursor.value();
    }

    void next() {
        if (++sinceReopen < REOPEN_INTERVAL) {
            cursor.next();
            return;
        }
        sinceReopen = 0;
        byte[] last = cursor.key();
        cursor.close();
        cursor = keyspace.cursor();
        if (cursor.seek(last) && Arrays.equals(cursor.key(), last)) cursor.next();
    }

    @Override
    public void close() {
        cursor.close();
    }
}
