package tinycdxserver;

import org.rocksdb.*;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class DataStore implements Closeable {
    private final File dataDir;
    private final Map<String, RocksDB> indexes = new ConcurrentHashMap<String, RocksDB>();
    private final Predicate<Capture> filter;

    public DataStore(File dataDir, Predicate<Capture> filter) {
        this.dataDir = dataDir;
        this.filter = filter;
    }

    public Index getIndex(String collection) throws IOException {
        return getIndex(collection, false);
    }

    public Index getIndex(String collection, boolean createAllowed) throws IOException {
        RocksDB db = indexes.get(collection);
        if (db != null) {
            return new Index(db, filter);
        }
        return new Index(openDb(collection, createAllowed), filter);
    }

    private synchronized RocksDB openDb(String collection, boolean createAllowed) throws IOException {
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

        try {
            BlockBasedTableConfig tableConfig = new BlockBasedTableConfig();
            tableConfig.setBlockSize(22 * 1024); // approximately compresses to < 8 kB

            Options options = new Options();
            options.createStatistics();
            options.setCreateIfMissing(true);
            options.setCompactionStyle(CompactionStyle.LEVEL);
            options.setWriteBufferSize(64 * 1024 * 1024);
            options.setTargetFileSizeBase(64 * 1024 * 1024);
            options.setMaxBytesForLevelBase(512 * 1024 * 1024);
            options.setTargetFileSizeMultiplier(2);
            options.setCompressionType(CompressionType.SNAPPY_COMPRESSION);
            options.setTableFormatConfig(tableConfig);
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

    public void close() {
        for (RocksDB index : indexes.values()) {
            index.close();
        }
        indexes.clear();
    }

    public List<String> listCollections() {
        List<String> collections = new ArrayList<String>();
        for (File f : dataDir.listFiles()) {
            if (f.isDirectory() && isValidCollectionName(f.getName())) {
                collections.add(f.getName());
            }
        }
        return collections;
    }
}
