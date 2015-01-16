package tinycdxserver;

import org.fusesource.leveldbjni.JniDBFactory;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;

import java.io.File;
import java.io.IOException;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * A thin wrapper around LevelDB which stores CDX records in a space-efficient binary format.
 *
 * See {@link tinycdxserver.Record} for details of the binary encoding.
 */
public class Index {
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
}
