package outbackcdx;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.rocksdb.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
        long publicPolicyId = accessControl.put(publicPolicy);

        AccessPolicy staffOnly = new AccessPolicy();
        staffOnly.name = "Staff Only";
        staffOnly.accessPoints.add("staff");
        long staffOnlyPolicyId = accessControl.put(staffOnly);


        AccessRule rule = new AccessRule();
        rule.urlPatterns.add("*.gov.au");
        rule.policyId = publicPolicyId;

        long ruleId = accessControl.put(rule);
        assertEquals(rule, accessControl.rule(ruleId));

        AccessRule rule2 = new AccessRule();
        rule2.urlPatterns.add("*.nla.gov.au");
        rule2.policyId = publicPolicyId;
        accessControl.put(rule2);

        AccessRule rule3 = new AccessRule();
        rule3.urlPatterns.add("*.example.gov.au");
        rule3.policyId = staffOnlyPolicyId;
        rule3.publicMessage = "Explanatory message";
        accessControl.put(rule3);


        assertEquals(asList(rule, rule2), accessControl.rulesForUrl("http://nla.gov.au/hello.html"));
        assertEquals(asList(rule, rule2, rule3), new ArrayList<>(accessControl.list()));

        {
            AccessDecision decision = accessControl.checkAccess("public", "http://nla.gov.au/hello.html", new Date(), new Date());
            assertTrue(decision.isAllowed());
        }
        {
            AccessDecision decision = accessControl.checkAccess("public", "http://restricted.example.gov.au/hello.html", new Date(), new Date());
            assertFalse(decision.isAllowed());
            assertEquals("Explanatory message", decision.getPublicMessage());
        }


        AccessRule rule4 = new AccessRule();
        rule4.urlPatterns.add("http://www.example.org/particular/page.htm");
        rule4.policyId = staffOnlyPolicyId;
        accessControl.put(rule4);

        {
            AccessDecision decision = accessControl.checkAccess("public", "http://www.example.org:80/particular/page.htm", new Date(), new Date());
            assertFalse(decision.isAllowed());
        }
    }


    @Test
    public void testPattern() {
        assertEquals("au,gov,", AccessControl.toSsurtPrefix("*.gov.au"));
        assertEquals("au,gov,", AccessControl.toSsurtPrefix("*.GOV.AU"));
        assertEquals("com,example,//:http/foo/", AccessControl.toSsurtPrefix("http://EXAMPLE.com/foo/*"));
        assertEquals("com,example,//:http/foo/ ", AccessControl.toSsurtPrefix("http://example.com/foo/"));
    }

}

