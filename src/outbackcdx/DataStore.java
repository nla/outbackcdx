package outbackcdx;

import org.rocksdb.*;
import org.rocksdb.Options;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

public class DataStore implements Closeable {
    public static final String COLLECTION_PATTERN = "[A-Za-z0-9_-]+";

    private final File dataDir;
    private final Map<String, Index> indexes = new ConcurrentHashMap<String, Index>();

    public long replicationWindow;

    public DataStore(File dataDir, long replicationWindow) {
        this.dataDir = dataDir;
        this.replicationWindow = replicationWindow;
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
            dbOptions.setMaxBackgroundCompactions(Math.min(8, Runtime.getRuntime().availableProcessors()));
            dbOptions.setAvoidFlushDuringRecovery(true);

            // replication will be available this far back in time (in seconds)
            if (replicationWindow > 0) {
                dbOptions.setWalTtlSeconds(replicationWindow);
            }

            ColumnFamilyOptions cfOptions = new ColumnFamilyOptions();
            configureColumnFamily(cfOptions);

            List<ColumnFamilyDescriptor> cfDescriptors;
            if (FeatureFlags.experimentalAccessControl()) {
                cfDescriptors = Arrays.asList(
                        new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOptions),
                        new ColumnFamilyDescriptor("alias".getBytes(UTF_8), cfOptions),
                        new ColumnFamilyDescriptor("access-rule".getBytes(UTF_8), cfOptions),
                        new ColumnFamilyDescriptor("access-policy".getBytes(UTF_8), cfOptions)
                );
            } else {
                cfDescriptors = Arrays.asList(
                        new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOptions),
                        new ColumnFamilyDescriptor("alias".getBytes(UTF_8), cfOptions));
            }

            createColumnFamiliesIfNotExists(options, dbOptions, path.toString(), cfDescriptors);

            List<ColumnFamilyHandle> cfHandles = new ArrayList<>(cfDescriptors.size());
            RocksDB db = RocksDB.open(dbOptions, path.toString(), cfDescriptors, cfHandles);

            AccessControl accessControl = null;
            if (FeatureFlags.experimentalAccessControl()) {
                accessControl = new AccessControl(db, cfHandles.get(2), cfHandles.get(3));
            }

            index = new Index(collection, db, cfHandles.get(0), cfHandles.get(1), accessControl);
            indexes.put(collection, index);
            return index;
        } catch (RocksDBException e) {
            throw new IOException(e);
        }
    }

    private void configureColumnFamily(Options cfOptions) throws RocksDBException {
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

    private void configureColumnFamily(ColumnFamilyOptions cfOptions) throws RocksDBException {
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

    private void createColumnFamiliesIfNotExists(Options options, DBOptions dbOptions, String path, List<ColumnFamilyDescriptor> cfDescriptors) throws RocksDBException {
        List<ColumnFamilyDescriptor> existing = new ArrayList<>();
        List<ColumnFamilyDescriptor> toCreate = new ArrayList<>();
        Set<String> cfNames = RocksDB.listColumnFamilies(options, path)
                .stream().map(bytes -> new String(bytes, UTF_8))
                .collect(Collectors.toSet());
        for (ColumnFamilyDescriptor cfDesc : cfDescriptors) {
            if (cfNames.remove(new String(cfDesc.columnFamilyName(), UTF_8))) {
                existing.add(cfDesc);
            } else {
                toCreate.add(cfDesc);
            }
        }

        if (!cfNames.isEmpty()) {
            throw new RuntimeException("database may be too new: unexpected column family: " + cfNames.iterator().next());
        }

        // default CF is created automatically in empty db, exclude it
        if (existing.isEmpty()) {
            ColumnFamilyDescriptor defaultCf = cfDescriptors.get(0);
            existing.add(defaultCf);
            toCreate.remove(defaultCf);
        }

        List<ColumnFamilyHandle> handles = new ArrayList<>(existing.size());
        try (RocksDB db = RocksDB.open(dbOptions, path, existing, handles);) {
            for (ColumnFamilyDescriptor descriptor : toCreate) {
                try (ColumnFamilyHandle cf = db.createColumnFamily(descriptor)) {
                }
            }
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
        collections.sort(Comparator.naturalOrder());
        return collections;
    }
}
