package tinycdxserver;

import org.fusesource.leveldbjni.JniDBFactory;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.Options;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DataStore implements Closeable {
    private final File dataDir;
    private final Map<String, DB> indexes = new ConcurrentHashMap<String, DB>();

    public DataStore(File dataDir) {
        this.dataDir = dataDir;
    }

    public DB getIndex(String collection) throws IOException {
        return getIndex(collection, false);
    }

    public DB getIndex(String collection, boolean createAllowed) throws IOException {
        DB index = indexes.get(collection);
        if (index != null) {
            return index;
        }
        return openIndex(collection, createAllowed);
    }

    private synchronized DB openIndex(String collection, boolean createAllowed) throws IOException {
        if (!isValidCollectionName(collection)) {
            throw new IllegalArgumentException("Invalid collection name");
        }
        DB index = indexes.get(collection);
        if (index != null) {
            return index;
        }
        File path = new File(dataDir, collection);
        if (!createAllowed && !path.isDirectory()) {
            return null;
        }
        Options options = new Options();
        options.createIfMissing(createAllowed);
        index = JniDBFactory.factory.open(path, options);
        indexes.put(collection, index);
        return index;
    }

    private static boolean isValidCollectionName(String collection) {
        return collection.matches("^[A-Za-z0-9_-]+$");
    }

    @Override
    public void close() {
        for (DB index : indexes.values()) {
            try {
                index.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        indexes.clear();
    }
}
