package tinycdxserver;

import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Predicate;

public class Index {
    final RocksDB db;

    public Index(RocksDB db) {
        this.db = db;
    }

    public Iterable<Resource> prefixQuery(final String urlPrefix) {
        return () -> new Resources(new Captures(db, urlPrefix, record -> record.urlkey.startsWith(urlPrefix)));
    }

    public Iterable<Capture> query(String url) {
        return () -> new Captures(db, url, record -> record.urlkey.equals(url));
    }

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

        public Captures(RocksDB db, String url, Predicate<Capture> scope) {
            final RocksIterator it = db.newIterator();
            it.seek(Capture.encodeKey(url, 0));
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
}
