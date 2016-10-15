package tinycdxserver;

import com.google.gson.Gson;
import com.googlecode.concurrenttrees.radix.node.concrete.DefaultCharArrayNodeFactory;
import com.googlecode.concurrenttrees.radixinverted.ConcurrentInvertedRadixTree;
import com.googlecode.concurrenttrees.radixinverted.InvertedRadixTree;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.charset.StandardCharsets.UTF_8;

public class AccessControl {
    final static Gson gson = new Gson();

    final InvertedRadixTree<List<AccessRule>> rulesBySurt = new ConcurrentInvertedRadixTree<List<AccessRule>>(new DefaultCharArrayNodeFactory());
    private final RocksDB db;
    private final ColumnFamilyHandle cf;
    private final AtomicLong nextId;

    public AccessControl(RocksDB db, ColumnFamilyHandle cf) {
        this.db = db;
        this.cf = cf;
        try (RocksIterator it = db.newIterator(cf)) {
            it.seekToLast();
            if (it.isValid()) {
                nextId = new AtomicLong(decodeKey(it.key()) + 1);
            } else {
                nextId = new AtomicLong(0);
            }
        }
    }

    public List<AccessRule> list() {
        return flatten(rulesBySurt.getValuesForKeysStartingWith(""));
    }

    public long put(AccessRule rule) throws RocksDBException {
        if (rule.id == null) {
            rule.id = nextId.getAndIncrement();
        }
        byte[] value = gson.toJson(rule).getBytes(UTF_8);
        db.put(cf, encodeKey(rule.id), value);

        synchronized (this) {
            for (String surt : rule.surts) {
                List<AccessRule> list = rulesBySurt.getValueForExactKey(surt);
                if (list == null) {
                    list = Collections.synchronizedList(new ArrayList<>());
                    rulesBySurt.put(surt, list);
                }
                list.add(rule);
            }
        }
        return rule.id;
    }

    public List<AccessRule> query(String surt) {
        return flatten(rulesBySurt.getValuesForKeysPrefixing(surt));
    }

    public AccessRule get(long ruleId) throws RocksDBException {
        byte[] data = db.get(cf, encodeKey(ruleId));
        if (data != null) {
            return gson.fromJson(new String(data, UTF_8), AccessRule.class);
        }
        return null;
    }

    static long decodeKey(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(BIG_ENDIAN).getLong(0);
    }

    static byte[] encodeKey(long ruleId) {
        return ByteBuffer.allocate(8).order(BIG_ENDIAN).putLong(ruleId).array();
    }

    static List<AccessRule> flatten(Iterable<List<AccessRule>> listsOfRules) {
        // XXX make lazy?
        ArrayList<AccessRule> result = new ArrayList<>();
        listsOfRules.forEach(result::addAll);
        return result;
    }
}
