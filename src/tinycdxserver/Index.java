package tinycdxserver;

import org.fusesource.leveldbjni.JniDBFactory;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.ReadOptions;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * A thin wrapper around LevelDB which stores CDX records in a space-efficient binary format.
 *
 * See {@link tinycdxserver.Record} for details of the binary encoding.
 */
public class Index implements Closeable {
    private final DB db;

    public Index(File file) throws IOException {
        Options options = new Options();
        options.createIfMissing(true);
        db = JniDBFactory.factory.open(file, options);
    }

    public void put(Record record) {
        db.put(record.encodeKey(), record.encodeValue());
    }

    public Record get(String keyurl, long timestamp) {
        byte[] key = Record.encodeKey(keyurl, timestamp);
        byte[] value = db.get(key);
        if (value == null) {
            return null;
        }
        return new Record(key, value);
    }

    private ResultSet range(final String startKeyurl, final long startTimestamp, final String endKeyurl, final long endTimestamp) {
        return new ResultSet(db, startKeyurl, startTimestamp, endKeyurl, endTimestamp);
    }

    public ResultSet get(final String keyurl) {
        return range(keyurl, 0, keyurl, Long.MAX_VALUE);
    }

    @Override
    public void close() throws IOException {
        db.close();
    }
}
