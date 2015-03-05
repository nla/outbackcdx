package tinycdxserver;

import org.rocksdb.CompactionStyle;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DataStore implements Closeable {
    private final File dataDir;
    private final Map<String, RocksDB> indexes = new ConcurrentHashMap<String, RocksDB>();

    public DataStore(File dataDir) {
        this.dataDir = dataDir;
    }

    public RocksDB getIndex(String collection) throws IOException {
        return getIndex(collection, false);
    }

    public RocksDB getIndex(String collection, boolean createAllowed) throws IOException {
        RocksDB index = indexes.get(collection);
        if (index != null) {
            return index;
        }
        return openIndex(collection, createAllowed);
    }

    private synchronized RocksDB openIndex(String collection, boolean createAllowed) throws IOException {
        if (!isValidCollectionName(collection)) {
            throw new IllegalArgumentException("Invalid collection name");
        }
        RocksDB index = indexes.get(collection);
        if (index != null) {
            return index;
        }
        File path = new File(dataDir, collection);
        if (!createAllowed && !path.isDirectory()) {
            return null;
        }
        Options options = new Options();
        options.setCreateIfMissing(true);
        options.setMaxBytesForLevelBase(32 * 1024 * 1024);
        try {
            index = RocksDB.open(options, path.toString());
        } catch (RocksDBException e) {
            throw new IOException(e);
        }
        indexes.put(collection, index);
        return index;
    }

    private static boolean isValidCollectionName(String collection) {
        return collection.matches("^[A-Za-z0-9_-]+$");
    }

    @Override
    public void close() {
        for (RocksDB index : indexes.values()) {
            index.close();
        }
        indexes.clear();
    }
}
