package outbackcdx;

import com.moaduda.onggi.Options;
import com.moaduda.onggi.Store;
import com.moaduda.onggi.StoreException;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;

public class DataStore implements Closeable {
    public static final String COLLECTION_PATTERN = "[A-Za-z0-9_-]+";
    /** The keyspace holding captures, named after RocksDB's default column family. */
    static final String DEFAULT_KEYSPACE = "default";

    private final File dataDir;
    private final Map<String, Index> indexes = new ConcurrentHashMap<>();
    private final Long replicationWindow;
    private final long scanCap;
    private final UrlCanonicalizer canonicalizer;

    public DataStore(File dataDir, int maxOpenSstFiles, Long replicationWindow, long scanCap, UrlCanonicalizer canonicalizer) {
        this.dataDir = dataDir;
        this.replicationWindow = replicationWindow;
        this.scanCap = scanCap;
        // maxOpenSstFiles is ignored: Onggi keeps no file open per table.
        if (canonicalizer == null) {
            canonicalizer = new UrlCanonicalizer();
        }
        this.canonicalizer = canonicalizer;
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

        Options options = Options.defaults().withCreateIfMissing(createAllowed);
        // if not null, replication data will be available this far back in time (in seconds)
        if (replicationWindow != null) {
            options = options.withWalRetention(Duration.ofSeconds(replicationWindow));
        }
        List<String> keyspaces = FeatureFlags.experimentalAccessControl()
                ? List.of(DEFAULT_KEYSPACE, "alias", "access-rule", "access-policy")
                : List.of(DEFAULT_KEYSPACE, "alias");
        Store store;
        try {
            store = Store.open(path.toPath(), options);
        } catch (StoreException e) {
            throw new IOException(e);
        }
        for (String name : store.keyspaceNames()) {
            if (!keyspaces.contains(name)) {
                store.close();
                throw new RuntimeException("database may be too new: unexpected keyspace: " + name);
            }
        }

        AccessControl accessControl = null;
        if (FeatureFlags.experimentalAccessControl()) {
            accessControl = new AccessControl(store.keyspace("access-rule"), store.keyspace("access-policy"));
        }

        index = new Index(collection, store, store.keyspace(DEFAULT_KEYSPACE), store.keyspace("alias"), accessControl, scanCap, canonicalizer);
        indexes.put(collection, index);
        return index;
    }

    private static boolean isValidCollectionName(String collection) {
        return collection.matches("^" + COLLECTION_PATTERN + "$");
    }

    public synchronized void close() {
        for (Index index : indexes.values()) {
            index.store.close();
        }
        indexes.clear();
    }

    public List<String> listCollections() {
        List<String> collections = new ArrayList<>();
        for (File f : dataDir.listFiles()) {
            if (f.isDirectory() && isValidCollectionName(f.getName())) {
                collections.add(f.getName());
            }
        }
        collections.sort(Comparator.naturalOrder());
        return collections;
    }
}
