package tinycdxserver;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DataStore implements Closeable {
    private final File dataDir;
    private final Map<String, Index> indexes = new ConcurrentHashMap<String, Index>();

    public DataStore(File dataDir) {
        this.dataDir = dataDir;
    }

    public Index getIndex(String collection) throws IOException {
        return getIndex(collection, false);
    }

    public Index getIndex(String collection, boolean createAllowed) throws IOException {
        Index index = indexes.get(collection);
        if (index != null) {
            return index;
        }
        return openIndex(collection, createAllowed);
    }

    private synchronized Index openIndex(String collection, boolean createAllowed) throws IOException {
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
        index = new Index(path);
        indexes.put(collection, index);
        return index;
    }

    private static boolean isValidCollectionName(String collection) {
        return collection.matches("^[A-Za-z0-9_-]+$");
    }

    @Override
    public void close() {
        for (Index index : indexes.values()) {
            try {
                index.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        indexes.clear();
    }
}
