package outbackcdx;

import org.junit.Test;

import static org.junit.Assert.*;

public class FilterTest {
    @Test
    public void testRegexFilter() {
        Capture one = new Capture();
        one.file = "one.warc.gz";
        one.status = 201;
        Capture two = new Capture();
        two.file = "two.warc.gz";
        two.status = 202;

        assertTrue(Filter.fromSpec("filename:one.*").test(one));
        assertFalse(Filter.fromSpec("filename:one.*").test(two));
        assertTrue(Filter.fromSpec("status:20.").test(one));
        assertTrue(Filter.fromSpec("status:20.").test(two));
        assertTrue(Filter.fromSpec("status:201").test(one));
        assertFalse(Filter.fromSpec("status:201").test(two));
        assertTrue(Filter.fromSpec("!status:201").test(two));
    }

    @Test
    public void testSubstringFilter() {
        Capture one = new Capture();
        one.file = "one.warc.gz";
        one.status = 201;
        Capture two = new Capture();
        two.file = "two.warc.gz";
        two.status = 202;

        assertTrue(Filter.fromSpec("~filename:one").test(one));
        assertFalse(Filter.fromSpec("~filename:one").test(two));
        assertTrue(Filter.fromSpec("~status:20").test(one));
        assertTrue(Filter.fromSpec("~status:20").test(two));
        assertTrue(Filter.fromSpec("~status:201").test(one));
        assertFalse(Filter.fromSpec("~status:201").test(two));
        assertTrue(Filter.fromSpec("~!status:201").test(two));
    }

    @Test
    public void testCollapse() {
        Capture one = new Capture();
        one.original = "http://example.com/collapse";
        one.timestamp = 20190101000000l;
        Capture two = new Capture();
        two.original = "http://example.com/collapse";
        two.timestamp = 20190101000005l;

        Filter originalCollapser = Filter.collapser("original");
        assertTrue(originalCollapser.test(one));
        assertFalse(originalCollapser.test(two));

        Filter t14Collapser = Filter.collapser("timestamp");
        assertTrue(t14Collapser.test(one));
        assertTrue(t14Collapser.test(two));

        Filter t13Collapser = Filter.collapser("timestamp:13");
        assertTrue(t13Collapser.test(one));
        assertFalse(t13Collapser.test(two));

        Filter t11Collapser = Filter.collapser("timestamp:11");
        assertTrue(t11Collapser.test(one));
        assertFalse(t11Collapser.test(two));
    }

    @Test(expected = IllegalArgumentException.class)
    public void bogusField() {
        Filter.fromSpec("bogus:.*");
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void noField() {
        // whole line filter not supported currently
        Filter.fromSpec(".*");
    }
}