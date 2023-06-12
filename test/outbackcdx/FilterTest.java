package outbackcdx;

import org.junit.Test;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

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
    public void testCollapseToFirst() {
        Capture one = new Capture();
        one.original = "http://example.com/collapse";
        one.timestamp = 20190101000000l;
        Capture two = new Capture();
        two.original = "http://example.com/collapse";
        two.timestamp = 20190101000005l;

        Filter originalCollapser = Filter.collapseToFirst("original");
        assertTrue(originalCollapser.test(one));
        assertFalse(originalCollapser.test(two));

        Filter t14Collapser = Filter.collapseToFirst("timestamp");
        assertTrue(t14Collapser.test(one));
        assertTrue(t14Collapser.test(two));

        Filter t13Collapser = Filter.collapseToFirst("timestamp:13");
        assertTrue(t13Collapser.test(one));
        assertFalse(t13Collapser.test(two));
    }

    public static class DummyCloseableIterator implements CloseableIterator<Capture> {
        private final Iterator<Capture> inner;

        public DummyCloseableIterator(Iterator<Capture> inner) {
            this.inner = inner;
        }

        @Override
        public void close() {

        }

        @Override
        public boolean hasNext() {
            return inner.hasNext();
        }

        @Override
        public Capture next() {
            return inner.next();
        }
    }

    public static Iterator<Capture> collapseToLastList(List<Capture> list, String spec) {
        return Filter.collapseToLast(new DummyCloseableIterator(list.iterator()), spec);
    }

    @Test
    public void testCollapseToLast() {
        Capture one = new Capture();
        one.original = "http://example.com/collapse";
        one.timestamp = 20190101000000l;
        Capture two = new Capture();
        two.original = "http://example.com/collapse";
        two.timestamp = 20190101000005l;
        List<Capture> list = Arrays.asList(one, two);

        Iterator<Capture> iter = collapseToLastList(list, "original");
        assertEquals(iter.next(), two);
        assertFalse(iter.hasNext());

        iter = collapseToLastList(list, "timestamp");
        assertEquals(iter.next(), one);
        assertEquals(iter.next(), two);
        assertFalse(iter.hasNext());

        iter = collapseToLastList(list, "timestamp:13");
        assertEquals(iter.next(), two);
        assertFalse(iter.hasNext());

        // sanity check wrapping empty iterator
        iter = collapseToLastList(Collections.<Capture>emptyList(), "original");
        assertFalse(iter.hasNext());

        // check that additional calls don't mess up iterator state
        iter = collapseToLastList(list, "timestamp");
        assertTrue(iter.hasNext());
        assertTrue(iter.hasNext());
        assertEquals(iter.next(), one);
        assertTrue(iter.hasNext());
        assertTrue(iter.hasNext());
        assertEquals(iter.next(), two);
        assertFalse(iter.hasNext());
        NoSuchElementException e = null;
        try {
            iter.next();
        } catch (NoSuchElementException e1) {
            e = e1;
        }
        assertNotNull(e);

        // check that additional calls don't mess up iterator state
        iter = collapseToLastList(list, "timestamp:13");
        assertTrue(iter.hasNext());
        assertTrue(iter.hasNext());
        assertEquals(iter.next(), two);
        assertFalse(iter.hasNext());
        assertFalse(iter.hasNext());
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