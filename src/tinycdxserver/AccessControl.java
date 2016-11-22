package tinycdxserver;

import com.googlecode.concurrenttrees.radix.node.concrete.DefaultCharArrayNodeFactory;
import com.googlecode.concurrenttrees.radixinverted.ConcurrentInvertedRadixTree;
import com.googlecode.concurrenttrees.radixinverted.InvertedRadixTree;
import org.netpreserve.urlcanon.Canonicalizer;
import org.netpreserve.urlcanon.ParsedUrl;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.charset.StandardCharsets.UTF_8;
import static tinycdxserver.Json.GSON;

/**
 * Manages a set of access control rules and policies. Rules are persisted
 * in RocksDB but are also kept in-memory in a radix tree for fast filtering
 * of results.
 */
class AccessControl {
    private final Map<Long,AccessPolicy> policies;
    private final Map<Long,AccessRule> rules;
    private final RulesBySsurt rulesBySurt;
    private final RocksDB db;
    private final ColumnFamilyHandle ruleCf, policyCf;
    private final AtomicLong nextRuleId, nextPolicyId;

    public AccessControl(RocksDB db, ColumnFamilyHandle ruleCf, ColumnFamilyHandle policyCf) throws RocksDBException {
        this.db = db;
        this.ruleCf = ruleCf;
        this.policyCf = policyCf;

        rules = loadRules(db, ruleCf);
        policies = loadPolicies(db, policyCf);

        rulesBySurt = new RulesBySsurt(rules.values());

        nextRuleId = new AtomicLong(calculateNextId(db, ruleCf));
        nextPolicyId = new AtomicLong(calculateNextId(db, policyCf));

        if (policies.isEmpty()) {
            // create some default policies
            put(new AccessPolicy("Public", "public", "staff"));
            put(new AccessPolicy("Staff Only", "staff"));
            put(new AccessPolicy("No Access"));
        }
    }

    private long calculateNextId(RocksDB db, ColumnFamilyHandle cf) {
        try (RocksIterator it = db.newIterator(cf)) {
            it.seekToLast();
            return it.isValid() ? decodeKey(it.key()) + 1 : 0;
        }
    }

    private static Map<Long,AccessPolicy> loadPolicies(RocksDB db, ColumnFamilyHandle policyCf) {
        Map<Long,AccessPolicy> map = new TreeMap<>();
        try (RocksIterator it = db.newIterator(policyCf)) {
            it.seekToFirst();
            while (it.isValid()) {
                AccessPolicy policy = GSON.fromJson(new String(it.value(), UTF_8), AccessPolicy.class);
                map.put(policy.id, policy);
                it.next();
            }
        }
        return map;
    }

    private static Map<Long, AccessRule> loadRules(RocksDB db, ColumnFamilyHandle ruleCf) {
        Map<Long,AccessRule> map = new TreeMap<>();
        try (RocksIterator it = db.newIterator(ruleCf)) {
            it.seekToFirst();
            while (it.isValid()) {
                AccessRule rule = GSON.fromJson(new String(it.value(), UTF_8), AccessRule.class);
                map.put(rule.id, rule);
                it.next();
            }
        }
        return map;
    }

    /**
     * List all access control rules in the database.
     */
    public Collection<AccessRule> list() {
        return rules.values();
    }

    /**
     * Save an access control rule to the database.
     */
    public Long put(AccessRule rule) throws RocksDBException {
        if (rule.policyId == null || policies.get(rule.policyId) == null) {
            throw new IllegalArgumentException("no such policyId: " + rule.policyId);
        }

        Long generatedId = null;
        if (rule.id == null) {
            generatedId = rule.id = nextRuleId.getAndIncrement();
        }
        byte[] value = GSON.toJson(rule).getBytes(UTF_8);
        db.put(ruleCf, encodeKey(rule.id), value);

        AccessRule previous = rules.put(rule.id, rule);
        if (previous != null) {
            rulesBySurt.remove(previous);
        }
        // XXX: race
        rulesBySurt.put(rule);

        return generatedId;
    }

    /**
     * Save an access control policy to the database.
     */
    public Long put(AccessPolicy policy) throws RocksDBException {
        Long generatedId = null;
        if (policy.id == null) {
            generatedId = policy.id = nextPolicyId.getAndIncrement();
        }
        byte[] value = GSON.toJson(policy).getBytes(UTF_8);
        db.put(policyCf, encodeKey(policy.id), value);
        policies.put(policy.id, policy);
        return generatedId;
    }

    /**
     * Find all rules that may apply to the given SSURT.
     */
    List<AccessRule> rulesForSsurt(String ssurt) {
        return rulesBySurt.prefixing(ssurt);
    }

    /**
     * Find all rules that may apply to the given URL.
     */
    List<AccessRule> rulesForUrl(String url) {
        ParsedUrl parsed = ParsedUrl.parseUrl(url);
        Canonicalizer.WHATWG.canonicalize(parsed);
        String ssurt = parsed.ssurt().toString();
        return rulesBySurt.prefixing(ssurt);
    }

    /**
     * Returns a predicate which can be used to filter a list of captures.
     */
    public Predicate<Capture> filter(String accessPoint, Date accessTime) {
        return new Predicate<Capture>() {
            String previousUrl = null;
            List<AccessRule> previousRules = null;

            @Override
            public boolean test(Capture capture) {
                AccessRule matching = null;
                List<AccessRule> rules;

                // we often process runs of identical urls so cache the last SSURT
                if (Objects.equals(previousUrl, capture.original)) {
                    rules = previousRules;
                } else {
                    previousUrl = capture.original;
                    previousRules = rules = rulesForUrl(capture.original);
                }

                for (AccessRule rule : rules) {
                    if (rule.matchesDates(capture.date(), accessTime)) {
                        matching = rule;
                    }
                }

                if (matching != null) {
                    AccessPolicy policy = policies.get(matching.policyId);
                    if (policy != null && !policy.accessPoints.contains(accessPoint)) {
                        return false;
                    }
                }
                return true;
            }
        };
    }

    /**
     * Lookup an access rule by id.
     */
    public AccessRule rule(long ruleId) throws RocksDBException {
        return rules.get(ruleId);
    }

    static long decodeKey(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(BIG_ENDIAN).getLong(0);
    }

    static byte[] encodeKey(long ruleId) {
        return ByteBuffer.allocate(8).order(BIG_ENDIAN).putLong(ruleId).array();
    }

    public AccessPolicy policy(long policyId) {
        return policies.get(policyId);
    }

    public boolean deleteRule(long ruleId) throws RocksDBException {
        // XXX: sync?
        AccessRule rule = rules.remove(ruleId);
        if (rule == null) {
            return false;
        }
        rulesBySurt.remove(rule);
        db.remove(ruleCf, encodeKey(ruleId));
        return true;
    }

    public Collection<AccessPolicy> listPolicies() {
        return policies.values();
    }

    /**
     * A secondary index for looking up access control URLs which prefix a
     * given SURT.
     *
     * As the radix tree library can't handle an empty keys we prefix every key
     * by " " to allow for a default rule.
     */
    static class RulesBySsurt {
        private final InvertedRadixTree<List<AccessRule>> tree;

        RulesBySsurt(Collection<AccessRule> rules) {
            tree = new ConcurrentInvertedRadixTree<>(new DefaultCharArrayNodeFactory());
            for (AccessRule rule: rules) {
                put(rule);
            }
        }

        /**
         * Add an AccessRule to the radix tree. The rule will be added multiple times,
         * once for each SURT prefix.
         */
        void put(AccessRule rule) {
            rule.ssurtPrefixes().forEach(ssurtPrefix -> {
                String key = " " + ssurtPrefix;
                List<AccessRule> list = tree.getValueForExactKey(key);
                if (list == null) {
                    list = Collections.synchronizedList(new ArrayList<>());
                    tree.put(key, list);
                }
                list.add(rule);
            });
        }

        void remove(AccessRule rule) {
            rule.ssurtPrefixes().forEach(ssurtPrefix -> {
                List<AccessRule> list = tree.getValueForExactKey(" " + ssurtPrefix);
                list.remove(rule);
            });
        }

        List<AccessRule> prefixing(String ssurt) {
            return flatten(tree.getValuesForKeysPrefixing(" " + ssurt + " "));
        }

        static List<AccessRule> flatten(Iterable<List<AccessRule>> listsOfRules) {
            // XXX make lazy?
            ArrayList<AccessRule> result = new ArrayList<>();
            listsOfRules.forEach(result::addAll);
            return result;
        }
    }
}
