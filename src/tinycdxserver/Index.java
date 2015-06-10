package tinycdxserver;

import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Predicate;

/**
 * Wraps RocksDB with a higher-level query interface.
 */
public class Index {
    final RocksDB db;
    final Predicate<Capture> filter;

    public Index(RocksDB db) {
        this(db, null);
    }

    public Index(RocksDB db, Predicate<Capture> filter) {
        this.db = db;
        this.filter = filter;
    }

    /**
     * Returns all resources (URLs) that match the given prefix.
     */
    public Iterable<Resource> prefixQuery(String urlPrefix) {
        return () -> new Resources(filteredCaptures(urlPrefix, record -> record.urlkey.startsWith(urlPrefix)));
    }

    /**
     * Returns all captures for the given url.
     */
    public Iterable<Capture> query(String url) {
        return () -> filteredCaptures(url, record -> record.urlkey.equals(url));
    }

    private Iterator<Capture> filteredCaptures(String queryUrl, Predicate<Capture> scope) {
        Iterator<Capture> captures = new Captures(db, queryUrl, scope);
        if (filter != null) {
            captures = new FilteringIterator<>(captures, filter);
        }
        return captures;
    }

    /**
     * Iterates capture records in RocksDb, starting from queryUrl and continuing until scope returns false.
     */
    private static class Captures implements Iterator<Capture> {
        private final RocksIterator it;
        private final Predicate<Capture> scope;
        private Capture capture = null;
        private boolean exhausted = false;

        @Override
        protected void finalize() throws Throwable {
            it.dispose();
            super.finalize();
        }

        public Captures(RocksDB db, String queryUrl, Predicate<Capture> scope) {
            final RocksIterator it = db.newIterator();
            it.seek(Capture.encodeKey(queryUrl, 0));
            this.scope = scope;
            this.it = it;
        }

        public boolean hasNext() {
            if (exhausted) {
                return false;
            }
            if (capture == null && it.isValid()) {
                capture = new Capture(it.key(), it.value());
            }
            if (capture == null || !scope.test(capture)) {
                capture = null;
                exhausted = true;
                it.dispose();
                return false;
            }
            return true;
        }

        public Capture next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Capture capture = this.capture;
            this.capture = null;
            it.next();
            return capture;
        }
    }

    /**
     * Groups together all captures of the same URL.
     */
    private static class Resources implements Iterator<Resource> {
        private final Iterator<Capture> captures;
        private Capture capture = null;

        Resources(Iterator<Capture> captures) {
            this.captures = captures;
        }

        public boolean hasNext() {
            return capture != null || captures.hasNext();
        }

        public Resource next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Resource result = new Resource();
            String previousDigest = null;
            if (capture == null) {
                capture = captures.next();
            }
            result.firstCapture = capture;
            while (capture.urlkey.equals(result.firstCapture.urlkey)) {
                if (previousDigest == null || !previousDigest.equals(capture.digest)) {
                    result.versions++;
                    previousDigest = capture.digest;
                }
                result.captures++;
                if (!captures.hasNext()) {
                    capture = null;
                    break;
                }
                capture = captures.next();
            }
            result.lastCapture = capture;
            return result;
        }
    }

    /**
     * Wraps another iterator and only returns elements that match the given predicate.
     */
    private static class FilteringIterator<T> implements Iterator<T> {
        private final Iterator<T> iterator;
        private final Predicate<T> predicate;
        private T next;
        private boolean holdingNext = false;

        public FilteringIterator(Iterator<T> iterator, Predicate<T> predicate) {
            this.iterator = iterator;
            this.predicate = predicate;
        }

        @Override
        public boolean hasNext() {
            while (!holdingNext && iterator.hasNext()) {
                T candidate = iterator.next();
                if (predicate.test(candidate)) {
                    next = candidate;
                    holdingNext = true;
                }
            }
            return holdingNext;
        }

        @Override
        public T next() {
            if (hasNext()) {
                T result = next;
                holdingNext = false;
                next = null;
                return result;
            } else {
                throw new NoSuchElementException();
            }
        }
    }

}
