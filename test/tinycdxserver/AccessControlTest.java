package tinycdxserver;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.rocksdb.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;

public class AccessControlTest {

    private static AccessControl accessControl;
    private static RocksDB db;
    private static ColumnFamilyHandle ruleCf, policyCf;
    private static RocksMemEnv env;

    @BeforeClass
    public static void setUp() throws RocksDBException {
        RocksDB.loadLibrary();
        env = new RocksMemEnv();
        try (Options options = new Options()
                     .setCreateIfMissing(true)
                     .setEnv(env)) {
            db = RocksDB.open(options, "test");
            ruleCf = db.getDefaultColumnFamily();
            policyCf = db.createColumnFamily(new ColumnFamilyDescriptor("policies".getBytes(StandardCharsets.UTF_8)));
            accessControl = new AccessControl(db, ruleCf, policyCf);
        }
    }

    @AfterClass
    public static void tearDown() {
        ruleCf.close();
        policyCf.close();
        db.close();
        env.close();
    }

    @Test
    public void test() throws RocksDBException {
        AccessPolicy publicPolicy = new AccessPolicy();
        publicPolicy.name = "Public";
        publicPolicy.accessPoints.add("public");
        publicPolicy.accessPoints.add("staff");
        long policyId = accessControl.put(publicPolicy);

        AccessRule rule = new AccessRule();
        rule.urlPatterns.add("(au,gov,");
        rule.policyId = policyId;

        long ruleId = accessControl.put(rule);
        assertEquals(rule, accessControl.rule(ruleId));

        AccessRule rule2 = new AccessRule();
        rule2.urlPatterns.add("(au,gov,nla,");
        rule2.policyId = policyId;
        accessControl.put(rule2);

        AccessRule rule3 = new AccessRule();
        rule3.urlPatterns.add("(au,gov,example,");
        rule3.policyId = policyId;
        accessControl.put(rule3);

        assertEquals(asList(rule, rule2), accessControl.rulesForSsurt("(au,gov,nla,)/hello.html"));
        assertEquals(asList(rule, rule2, rule3), new ArrayList<>(accessControl.list()));


        // patterns. *.gov.au
    }
}

