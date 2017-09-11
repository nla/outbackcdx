package outbackcdx;

import com.googlecode.concurrenttrees.radix.node.concrete.DefaultCharArrayNodeFactory;
import com.googlecode.concurrenttrees.radixinverted.ConcurrentInvertedRadixTree;
import com.googlecode.concurrenttrees.radixinverted.InvertedRadixTree;
import org.netpreserve.urlcanon.ByteString;
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
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.charset.StandardCharsets.UTF_8;
import static outbackcdx.Json.GSON;

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
            rule.created = new Date();
        }

        rule.modified = new Date();

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
        return rulesBySurt.prefixing(canonSsurt(url));
    }

    /**
     * Canonicalize and return the SURT form.
     *
     * - perform WHATWG canonicalization
     * - lowercase the path
     * - remove the fragment
     * - remove www. prefix from hostname
     * - replace https scheme with http
     *
     * These rules are a little aggressive to make defining rules less error prone.
     *
     * TODO: query string?
     *
     * TODO: reconcile this with UrlCanonicalizer. We should probably switch over to urlcanon as its a more robust
     * canonicalizer but a change will require rebuilding the index. Maybe keep both implementations and allow an
     * offline upgrade to be run?
     */
    static String canonSsurt(String url) {
        ParsedUrl parsed = ParsedUrl.parseUrl(url);
        Canonicalizer.WHATWG.canonicalize(parsed);
        parsed.setPath(parsed.getPath().asciiLowerCase());
        parsed.setFragment(ByteString.EMPTY);
        parsed.setHashSign(ByteString.EMPTY);
        parsed.setHost(parsed.getHost().replaceAll(UrlCanonicalizer.WWW_PREFIX, ""));
        if (parsed.getScheme().toString().equals("https")) {
            parsed.setScheme(new ByteString("http"));
        }
        return parsed.ssurt().toString();
    }

    private static void reverseDomain(String host, StringBuilder out) {
        int i = host.lastIndexOf('.');
        int j = host.length();
        while (i != -1) {
            out.append(host, i + 1, j);
            out.append(',');
            j = i;
            i = host.lastIndexOf('.', i - 1);
        }
        out.append(host, 0, j);
        out.append(',');
    }

    /**
     * Converts an exact URL, a URL containing pywb-style "*" wildcards to a SSURT prefix.
     * SSURTs are passed through unaltered. Exact matches are suffixed with a space.
     */
     static String toSsurtPrefix(String pattern) {
        if (pattern.startsWith("*.")) {
            if (pattern.contains("/")) {
                throw new IllegalArgumentException("can't use a domain wildcard with a path");
            }
            StringBuilder out = new StringBuilder();
            reverseDomain(pattern.substring(2), out);
            return out.toString().toLowerCase();
        } else if (pattern.endsWith("*")) {
            return AccessControl.canonSsurt(pattern.substring(0, pattern.length() - 1));
        } else {
            return AccessControl.canonSsurt(pattern.substring(0, pattern.length())) + " ";
        }
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

                return checkAccess(accessPoint, capture.date(), accessTime, rules).isAllowed();
            }
        };
    }

    public AccessDecision checkAccess(String accessPoint, String url, Date captureTime, Date accessTime) {
        List<AccessRule> rules = rulesForUrl(url);
        return checkAccess(accessPoint, captureTime, accessTime, rules);
    }

    private AccessDecision checkAccess(String accessPoint, Date captureTime, Date accessTime, List<AccessRule> rules) {
        AccessRule matching = null;

        for (AccessRule rule : rules) {
            if (rule.matchesDates(captureTime, accessTime)) {
                matching = rule;
            }
        }

        if (matching != null) {
            AccessPolicy policy = policies.get(matching.policyId);
            boolean allowed = true;
            if (policy != null && !policy.accessPoints.contains(accessPoint)) {
                allowed = false;
            }
            return new AccessDecision(allowed, matching, policy);
        }
        return new AccessDecision(true, null, null);
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
        private static final Logger log = Logger.getLogger(RulesBySsurt.class.getName());
        private final InvertedRadixTree<List<AccessRule>> tree;

        RulesBySsurt(Collection<AccessRule> rules) {
            tree = new ConcurrentInvertedRadixTree<>(new DefaultCharArrayNodeFactory());
            for (AccessRule rule: rules) {
                try {
                    put(rule);
                } catch (IllegalArgumentException e) {
                    log.log(Level.WARNING, "Skipping invalid access rule: " + rule.id, e);
                }
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
