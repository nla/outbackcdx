package outbackcdx;

import org.junit.Test;

import static org.junit.Assert.*;

public class RegexFilterTest {
    @Test
    public void test() {
        Capture one = new Capture();
        one.file = "one.warc.gz";
        one.status = 201;
        Capture two = new Capture();
        two.file = "two.warc.gz";
        two.status = 202;

        assertTrue(new RegexFilter("filename:one.*").test(one));
        assertFalse(new RegexFilter("filename:one.*").test(two));
        assertTrue(new RegexFilter("status:20.").test(one));
        assertTrue(new RegexFilter("status:20.").test(two));
        assertTrue(new RegexFilter("status:201").test(one));
        assertFalse(new RegexFilter("status:201").test(two));
        assertTrue(new RegexFilter("!status:201").test(two));
    }

    @Test(expected = IllegalArgumentException.class)
    public void bogusField() {
        new RegexFilter("bogus:.*");
    }
}