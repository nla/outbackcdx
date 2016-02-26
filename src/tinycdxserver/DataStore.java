package tinycdxserver;

import org.rocksdb.*;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class DataStore implements Closeable {
    public static final String COLLECTION_PATTERN = "[A-Za-z0-9_-]+";

    private final File dataDir;
    private final Map<String, Index> indexes = new ConcurrentHashMap<String, Index>();
    private final Predicate<Capture> filter;

    public DataStore(File dataDir, Predicate<Capture> filter) {
        this.dataDir = dataDir;
        this.filter = filter;
    }

    public Index getIndex(String collection) throws IOException {
        return getIndex(collection, false);
    }

    public Index getIndex(String collection, boolean createAllowed) throws IOException {
        Index index = indexes.get(collection);
        if (index != null) {
            return index;
        }
        return openDb(collection, createAllowed);
    }

    private synchronized Index openDb(String collection, boolean createAllowed) throws IOException {
        if (!isValidCollectionName(collection)) {
            throw new IllegalArgumentException("Invalid collection name");
        }
        Index index = indexes.get(collection);
        if (index != null) {
            return index;
        }
        File path = new File(dataDir, collection);
        if (!createAllowed && !path.isDirectory()) {
            return null;
        }

        try {
            Options options = new Options();
            options.setCreateIfMissing(createAllowed);
            configureColumnFamily(options);

            DBOptions dbOptions = new DBOptions();
            dbOptions.setCreateIfMissing(createAllowed);

            ColumnFamilyOptions cfOptions = new ColumnFamilyOptions();
            configureColumnFamily(cfOptions);

            List<ColumnFamilyDescriptor> cfDescriptors = Arrays.asList(
                    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOptions),
                    new ColumnFamilyDescriptor("alias", cfOptions)
            );

            createColumnFamiliesIfNotExists(options, path.toString(), cfDescriptors);

            List<ColumnFamilyHandle> cfHandles = new ArrayList<>(cfDescriptors.size());
            RocksDB db = RocksDB.open(dbOptions, path.toString(), cfDescriptors, cfHandles);

            index = new Index(db, filter, cfHandles.get(0), cfHandles.get(1));
            indexes.put(collection, index);
            return index;
        } catch (RocksDBException e) {
            throw new IOException(e);
        }
    }

    private void configureColumnFamily(ColumnFamilyOptionsInterface cfOptions) throws RocksDBException {
        BlockBasedTableConfig tableConfig = new BlockBasedTableConfig();
        tableConfig.setBlockSize(22 * 1024); // approximately compresses to < 8 kB

        cfOptions.setCompactionStyle(CompactionStyle.LEVEL);
        cfOptions.setWriteBufferSize(64 * 1024 * 1024);
        cfOptions.setTargetFileSizeBase(64 * 1024 * 1024);
        cfOptions.setMaxBytesForLevelBase(512 * 1024 * 1024);
        cfOptions.setTargetFileSizeMultiplier(2);
        cfOptions.setCompressionType(CompressionType.SNAPPY_COMPRESSION);
        cfOptions.setTableFormatConfig(tableConfig);
    }

    private void createColumnFamiliesIfNotExists(Options options, String path, List<ColumnFamilyDescriptor> cfDescriptors) throws RocksDBException {
        RocksDB db;
        try {
            db = RocksDB.open(options, path);
        } catch (RocksDBException e) {
            if (e.getMessage().contains("You have to open all column families")) {
                // TODO
                return;
            } else {
                throw e;
            }
        }
        try {
            for (ColumnFamilyDescriptor descriptor : cfDescriptors) {
                try {
                    db.createColumnFamily(descriptor).dispose();
                } catch (RocksDBException e) {
                    if (!e.getMessage().equals("Invalid argument: Column family already exists")) {
                        throw e;
                    }
                }
            }
        } finally {
            db.close();
        }
    }

    private static boolean isValidCollectionName(String collection) {
        return collection.matches("^" + COLLECTION_PATTERN + "$");
    }

    public synchronized void close() {
        for (Index index : indexes.values()) {
            index.db.close();
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
