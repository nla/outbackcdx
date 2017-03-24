package outbackcdx;

import org.rocksdb.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * Wraps RocksDB with a higher-level query interface.
 */
public class Index {
    final RocksDB db;
    final ColumnFamilyHandle defaultCF;
    final ColumnFamilyHandle aliasCF;
    final AccessControl accessControl;

    public Index(RocksDB db, ColumnFamilyHandle defaultCF, ColumnFamilyHandle aliasCF, AccessControl accessControl) {
        this.db = db;
        this.defaultCF = defaultCF;
        this.aliasCF = aliasCF;
        this.accessControl = accessControl;
    }

    /**
     * Returns all captures that match the given prefix.
     */
    public Iterable<Capture> prefixQuery(String surtPrefix, String accessPoint) {
        return () -> filteredCaptures(Capture.encodeKey(surtPrefix, 0), record -> record.urlkey.startsWith(surtPrefix), accessPoint, false);
    }

    /**
     * Returns all captures with keys in the given range.
     */
    public Iterable<Capture> rangeQuery(String startSurt, String endSurt, String accessPoint) {
        return () -> filteredCaptures(Capture.encodeKey(startSurt, 0), record -> record.urlkey.compareTo(endSurt) < 0, accessPoint, false);
    }

    /**
     * Returns all captures for the given url.
     */
    public Iterable<Capture> query(String surt, String accessPoint) {
        String urlkey = resolveAlias(surt);
        byte[] key = Capture.encodeKey(urlkey, 0);
        return () -> filteredCaptures(key, record -> record.urlkey.equals(urlkey), accessPoint, false);
    }

    /**
     * Returns all captures for the given url in reverse order.
     */
    public Iterable<Capture> reverseQuery(String surt, String accessPoint) {
        String urlkey = resolveAlias(surt);
        byte[] key = Capture.encodeKey(urlkey, 99999999999999L);
        return () -> filteredCaptures(key, record -> record.urlkey.equals(urlkey), accessPoint, true);
    }

    /**
     * Returns all captures for the given url ordered by distance from the given timestamp.
     */
    public Iterable<Capture> closestQuery(String surt, long targetTimestamp, String accessPoint) {
        String urlkey = resolveAlias(surt);
        byte[] key = Capture.encodeKey(urlkey, targetTimestamp);
        Predicate<Capture> scope = record -> record.urlkey.equals(urlkey);
        return () -> new ClosestTimestampIterator(targetTimestamp,
                filteredCaptures(key, scope, accessPoint, false),
                filteredCaptures(key, scope, accessPoint, true));
    }

    /**
     * Combines a forward iterator and backward iterator into order-by closest
     * distance to timestamp iterator.
     */
    static class ClosestTimestampIterator implements Iterator<Capture> {
        final long targetMillis;
        final Iterator<Capture> forwardIterator;
        final Iterator<Capture> backwardIterator;
        Capture nextForward = null;
        Capture nextBackward = null;

        ClosestTimestampIterator(long targetTimestamp, Iterator<Capture> forwardIterator, Iterator<Capture> backwardIterator) {
            this.targetMillis = Capture.parseTimestamp(targetTimestamp).getTime();
            this.forwardIterator = forwardIterator;
            this.backwardIterator = backwardIterator;
        }

        @Override
        public boolean hasNext() {
            return nextForward != null || nextBackward != null || forwardIterator.hasNext() || backwardIterator.hasNext();
        }

        @Override
        public Capture next() {
            if (nextForward == null && forwardIterator.hasNext()) {
                nextForward = forwardIterator.next();
            }
            if (nextBackward == null && backwardIterator.hasNext()) {
                nextBackward = backwardIterator.next();
            }

            // if one or the other iterator is exhausted pick from its rival
            if (nextForward == null) {
                if (nextBackward == null) {
                    throw new NoSuchElementException();
                }
                return pickBackward();
            } else if (nextBackward == null) {
                return pickForward();
            }

            // both are still active so pick the closest
            long forwardDistance = nextForward.date().getTime() - targetMillis;
            long backwardDistance = targetMillis - nextBackward.date().getTime();

            if (forwardDistance <= backwardDistance) {
                return pickForward();
            } else {
                return pickBackward();
            }
        }

        private Capture pickBackward() {
            Capture result = nextBackward;
            nextBackward = null;
            return result;
        }

        private Capture pickForward() {
            Capture result = nextForward;
            nextForward = null;
            return result;
        }
    }

    /**
     * Perform a query without first resolving aliases.
     */
    private Iterable<Capture> rawQuery(String key, String accessPoint, boolean reverse) {
        return () -> filteredCaptures(Capture.encodeKey(key, 0), record -> record.urlkey.equals(key), accessPoint, reverse);
    }

    /**
     * Returns all captures starting from the given key.
     */
    Iterable<Capture> capturesAfter(String start) {
        return () -> filteredCaptures(Capture.encodeKey(start, 0), record -> true, null, false);
    }

    public String resolveAlias(String surt) {
        try {
            byte[] resolved = db.get(aliasCF, surt.getBytes(StandardCharsets.US_ASCII));
            if (resolved != null) {
                return new String(resolved, StandardCharsets.US_ASCII);
            } else {
                return surt;
            }
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    private Iterator<Capture> filteredCaptures(byte[] key, Predicate<Capture> scope, String accessPoint, boolean reverse) {
        Iterator<Capture> captures = new Records<>(db, defaultCF, key, Capture::new, scope, reverse);
        if (accessPoint != null && accessControl != null) {
            captures = new FilteringIterator<>(captures, accessControl.filter(accessPoint, new Date()));
        }
        return captures;
    }

    public Iterable<Alias> listAliases(String start) {
        byte[] key = start.getBytes(US_ASCII);
        return () -> new Records<>(db, aliasCF, key, Alias::new, (alias) -> true, false);
    }

    public long estimatedRecordCount() {
        try {
            return db.getLongProperty("rocksdb.estimate-num-keys");
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    private interface RecordConstructor<T> {
        public T construct(byte[] key, byte[] value);
    }

    /**
     * Iterates capture records in RocksDb, starting from queryUrl and continuing until scope returns false.
     */
    private static class Records<T> implements Iterator<T> {
        private final RocksIterator it;
        private final Predicate<T> scope;
        private final RecordConstructor<T> constructor;
        private final boolean reverse;
        private T record = null;
        private boolean exhausted = false;

        @Override
        protected void finalize() throws Throwable {
            it.close();
            super.finalize();
        }

        public Records(RocksDB db, ColumnFamilyHandle columnFamilyHandle, byte[] startKey, RecordConstructor<T> constructor, Predicate<T> scope, boolean reverse) {
            final RocksIterator it = db.newIterator(columnFamilyHandle);
            it.seek(startKey);
            if (reverse) {
                if (it.isValid()) {
                    it.prev();
                } else {
                    it.seekToLast();
                }
            }
            this.constructor = constructor;
            this.scope = scope;
            this.it = it;
            this.reverse = reverse;
        }

        public boolean hasNext() {
            if (exhausted) {
                return false;
            }
            if (record == null && it.isValid()) {
                record = constructor.construct(it.key(), it.value());
            }
            if (record == null || !scope.test(record)) {
                record = null;
                exhausted = true;
                it.close();
                return false;
            }
            return true;
        }

        public T next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            T record = this.record;
            this.record = null;
            if (reverse) {
                it.prev();
            } else {
                it.next();
            }
            return record;
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

    public Batch beginUpdate() {
        return new Batch();
    }

    private void commitBatch(WriteBatch writeBatch) {
        try (WriteOptions options = new WriteOptions()) {
            options.setSync(true);
            db.write(options, writeBatch);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    public class Batch implements AutoCloseable {
        private WriteBatch dbBatch = new WriteBatch();
        private final Map<String, String> newAliases = new HashMap<>();

        private Batch() {
        }

        /**
         * Add a new capture to the index.  Existing captures with the same urlkey and timestamp will be overwritten.
         */
        public void putCapture(Capture capture) {
            String resolved = newAliases.get(capture.urlkey);
            if (resolved != null) {
                capture.urlkey = resolved;
            } else {
                capture.urlkey = resolveAlias(capture.urlkey);
            }
            dbBatch.put(capture.encodeKey(), capture.encodeValue());
        }

        /**
         * Adds a new alias to the index.  Updates existing captures affected by the new alias.
         */
        public void putAlias(String aliasSurt, String targetSurt) {
            if (aliasSurt.equals(targetSurt)) {
                return; // a self-referential alias is equivalent to no alias so don't bother storing it
            }
            dbBatch.put(aliasCF, aliasSurt.getBytes(US_ASCII), targetSurt.getBytes(US_ASCII));
            newAliases.put(aliasSurt, targetSurt);
            updateExistingRecordsWithNewAlias(dbBatch, aliasSurt, targetSurt);
        }

        public void commit() {
            commitBatch(dbBatch);

            /*
             * Most of the time this will do nothing as we've already updated existing captures in putAlias, but there's
             * a data race as another batch could have inserted some captures in bewtween putAlias() and commit().
             *
             * Rather than serializing updates let's just do a second pass after committing to catch any captures that
             * were added in the meantime.
             */
            updateExistingRecordsWithNewAliases();
        }

        private void updateExistingRecordsWithNewAliases() {
            try (WriteBatch wb = new WriteBatch()) {
                for (Map.Entry<String, String> entry : newAliases.entrySet()) {
                    updateExistingRecordsWithNewAlias(wb, entry.getKey(), entry.getValue());
                }
                commitBatch(wb);
            }
        }

        private void updateExistingRecordsWithNewAlias(WriteBatch wb, String aliasSurt, String targetSurt) {
            for (Capture capture : rawQuery(aliasSurt, null, false)) {
                wb.remove(capture.encodeKey());
                capture.urlkey = targetSurt;
                wb.put(capture.encodeKey(), capture.encodeValue());
            }
        }

        @Override
        public void close() {
            dbBatch.close();
        }
    }
}
