package tinycdxserver;

import org.junit.Test;
import org.rocksdb.*;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;

public class AccessControlTest {
    @Test
    public void test() throws RocksDBException {
        RocksDB.loadLibrary();
        try (RocksMemEnv env = new RocksMemEnv();
             Options options = new Options()
                     .setCreateIfMissing(true)
                     .setEnv(env);
             RocksDB db = RocksDB.open(options, "test");
             ColumnFamilyHandle cf = db.getDefaultColumnFamily()) {
            AccessControl index = new AccessControl(db, cf);

            AccessRule rule = new AccessRule();
            rule.surts.add("au,gov,");

            long ruleId = index.put(rule);
            assertEquals(rule, index.get(ruleId));

            AccessRule rule2 = new AccessRule();
            rule2.surts.add("au,gov,nla,");
            index.put(rule2);

            AccessRule rule3 = new AccessRule();
            rule3.surts.add("au,gov,example,");
            index.put(rule3);

            assertEquals(asList(rule, rule2), index.query("au,gov,nla,)/hello.html"));
            assertEquals(asList(rule, rule3, rule2), index.list());
        }
    }
}
