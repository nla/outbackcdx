package tinycdxserver;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.rocksdb.*;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;

public class AccessControlTest {

    private static AccessControl accessControl;
    private static RocksDB db;
    private static ColumnFamilyHandle cf;
    private static RocksMemEnv env;

    @BeforeClass
    public static void setUp() throws RocksDBException {
        RocksDB.loadLibrary();
        env = new RocksMemEnv();
        try (Options options = new Options()
                     .setCreateIfMissing(true)
                     .setEnv(env)) {
            db = RocksDB.open(options, "test");
            cf = db.getDefaultColumnFamily();
            accessControl = new AccessControl(db, cf);
        }
    }

    @AfterClass
    public static void tearDown() {
        cf.close();
        db.close();
        env.close();
    }

    @Test
    public void test() throws RocksDBException {
        AccessRule rule = new AccessRule();
        rule.surts.add("au,gov,");

        long ruleId = accessControl.put(rule);
        assertEquals(rule, accessControl.get(ruleId));

        AccessRule rule2 = new AccessRule();
        rule2.surts.add("au,gov,nla,");
        accessControl.put(rule2);

        AccessRule rule3 = new AccessRule();
        rule3.surts.add("au,gov,example,");
        accessControl.put(rule3);

        assertEquals(asList(rule, rule2), accessControl.query("au,gov,nla,)/hello.html"));
        assertEquals(asList(rule, rule3, rule2), accessControl.list());
    }
}

