package outbackcdx;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.moaduda.onggi.Store;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.time.Period;
import java.util.ArrayList;
import java.util.Date;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AccessControlTest {

    private static AccessControl accessControl;
    private static Store store;

    @ClassRule
    public static TemporaryFolder folder = new TemporaryFolder();

    @BeforeClass
    public static void setUp() throws IOException {
        store = Store.open(folder.newFolder("test").toPath());
        accessControl = new AccessControl(store.keyspace("default"), store.keyspace("policies"));
    }

    @AfterClass
    public static void tearDown() {
        store.close();
    }

    @Test
    public void test() {
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

        long ruleId = accessControl.put(rule, "anonymous");
        assertEquals(rule, accessControl.rule(ruleId));

        AccessRule rule2 = new AccessRule();
        rule2.urlPatterns.add("*.nla.gov.au");
        rule2.policyId = publicPolicyId;
        accessControl.put(rule2, "anonymous");

        AccessRule rule3 = new AccessRule();
        rule3.urlPatterns.add("*.example.gov.au");
        rule3.policyId = staffOnlyPolicyId;
        rule3.publicMessage = "Explanatory message";
        accessControl.put(rule3, "anonymous");


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
        accessControl.put(rule4, "anonymous");

        {
            AccessDecision decision = accessControl.checkAccess("public", "http://www.example.org:80/particular/page.htm", new Date(), new Date());
            assertFalse(decision.isAllowed());
        }
    }


    @Test
    public void testPattern() {
        assertEquals("au,gov,", AccessControl.toSsurtPrefix("*.gov.au"));
        assertEquals("au,gov,", AccessControl.toSsurtPrefix("*.GOV.AU"));
        assertEquals("com,example,//http:/foo", AccessControl.toSsurtPrefix("http://EXAMPLE.com/foo/*"));
        assertEquals("com,example,//http:/foo ", AccessControl.toSsurtPrefix("http://example.com/foo/"));
    }

    @Test
    public void testCanon() {
        FeatureFlags.setPandoraHacks(true);
        assertEquals("org,example,//http:/index.html?hello", AccessControl.canonSsurt("http://PANDORA.nla.gov.au/pan/12345/20160101-1234/www.example.org/INDEX.html?hello#world"));
    }

    @Test
    public void testJson() throws JsonProcessingException {
        AccessRule rule = new AccessRule();
        rule.period = Period.ofDays(1);
        String json = Json.JSON_MAPPER.writeValueAsString(rule);
        AccessRule rule2 = Json.JSON_MAPPER.readValue(json, AccessRule.class);
        assertEquals(rule.period, rule2.period);
    }

    @Test
    public void testWildcardPatternOverride() {
        // Create two policies: one public, one staff-only
        AccessPolicy publicPolicy = new AccessPolicy();
        publicPolicy.name = "Public Access";
        publicPolicy.accessPoints.add("public");
        publicPolicy.accessPoints.add("staff");
        long publicPolicyId = accessControl.put(publicPolicy);

        AccessPolicy staffOnlyPolicy = new AccessPolicy();
        staffOnlyPolicy.name = "Staff Only Access";
        staffOnlyPolicy.accessPoints.add("staff");
        long staffOnlyPolicyId = accessControl.put(staffOnlyPolicy);

        // Create a wildcard rule that restricts access to staff only
        AccessRule wildcardRule = new AccessRule();
        wildcardRule.urlPatterns.add("*");
        wildcardRule.policyId = staffOnlyPolicyId;
        wildcardRule.publicMessage = "Default access is restricted to staff";
        accessControl.put(wildcardRule, "test-user");

        // Create a specific rule that allows public access to a particular domain
        AccessRule specificRule = new AccessRule();
        specificRule.urlPatterns.add("*.open.gov.au");
        specificRule.policyId = publicPolicyId;
        accessControl.put(specificRule, "test-user");

        // Test that the wildcard rule restricts public access to a general URL
        {
            AccessDecision decision = accessControl.checkAccess("public", "http://example.com/test.html", new Date(), new Date());
            assertFalse("Wildcard rule should restrict public access to general URLs", decision.isAllowed());
            assertEquals("Default access is restricted to staff", decision.getPublicMessage());
        }

        // Test that the specific rule overrides the wildcard and allows public access
        {
            AccessDecision decision = accessControl.checkAccess("public", "http://test.open.gov.au/document.html", new Date(), new Date());
            assertTrue("Specific rule should override wildcard and allow public access", decision.isAllowed());
        }

        // Test that staff can access both general content (through wildcard) and specific content
        {
            AccessDecision decision = accessControl.checkAccess("staff", "http://example.com/test.html", new Date(), new Date());
            assertTrue("Staff should be able to access general content via wildcard rule", decision.isAllowed());
        }

        // Test that staff can also access the specifically allowed content
        {
            AccessDecision decision = accessControl.checkAccess("staff", "http://test.open.gov.au/document.html", new Date(), new Date());
            assertTrue("Staff should be able to access open content", decision.isAllowed());
        }
    }
}

