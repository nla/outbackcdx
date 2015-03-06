package tinycdxserver;

import org.rocksdb.*;

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

        try {
            BlockBasedTableConfig tableOptions = new BlockBasedTableConfig();
            tableOptions.setBlockCacheSize(64 * 1024 * 1024)
                    .setCacheNumShardBits(6)
                    .setBlockSizeDeviation(5)
                    .setBlockRestartInterval(10)
                    .setCacheIndexAndFilterBlocks(true)
                    .setHashIndexAllowCollision(false)
                    .setBlockCacheCompressedSize(64 * 1024 * 1024)
                    .setBlockCacheCompressedNumShardBits(10);


            Options options = new Options();
            options.setTableFormatConfig(tableOptions);
            options.createStatistics();
            options.setCreateIfMissing(true);
            options.setCompactionStyle(CompactionStyle.LEVEL);
            options.setWriteBufferSize(64 * 1024 * 1024);
            options.setTargetFileSizeBase(2 * 1024 * 1024);
            options.setMaxBytesForLevelBase(8 * 1024 * 1024);
            options.setTargetFileSizeMultiplier(2);
            options.setCompressionType(CompressionType.SNAPPY_COMPRESSION);
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
