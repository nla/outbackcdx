package tinycdxserver;

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
        return () -> filteredCaptures(surtPrefix, record -> record.urlkey.startsWith(surtPrefix), accessPoint);
    }

    /**
     * Returns all captures with keys in the given range.
     */
    public Iterable<Capture> rangeQuery(String startSurt, String endSurt, String accessPoint) {
        return () -> filteredCaptures(startSurt, record -> record.urlkey.compareTo(endSurt) < 0, accessPoint);
    }

    /**
     * Returns all captures for the given url.
     */
    public Iterable<Capture> query(String surt, String accessPoint) {
        return rawQuery(resolveAlias(surt), accessPoint);
    }

    /**
     * Perform a query without first resolving aliases.
     */
    private Iterable<Capture> rawQuery(String surt, String accessPoint) {
        return () -> filteredCaptures(surt, record -> record.urlkey.equals(surt), accessPoint);
    }

    /**
     * Returns all captures starting from the given key.
     */
    Iterable<Capture> capturesAfter(String start) {
        return () -> filteredCaptures(start, record -> true, null);
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

    private Iterator<Capture> filteredCaptures(String queryUrl, Predicate<Capture> scope, String accessPoint) {
        byte[] key = Capture.encodeKey(queryUrl, 0);
        Iterator<Capture> captures = new Records<>(db, defaultCF, key, Capture::new, scope);
        if (accessPoint != null) {
            captures = new FilteringIterator<>(captures, accessControl.filter(accessPoint, new Date()));
        }
        return captures;
    }

    public Iterable<Alias> listAliases(String start) {
        byte[] key = start.getBytes(US_ASCII);
        return () -> new Records<>(db, aliasCF, key, Alias::new, (alias) -> true);
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
        private T record = null;
        private boolean exhausted = false;

        @Override
        protected void finalize() throws Throwable {
            it.close();
            super.finalize();
        }

        public Records(RocksDB db, ColumnFamilyHandle columnFamilyHandle, byte[] startKey, RecordConstructor<T> constructor, Predicate<T> scope) {
            final RocksIterator it = db.newIterator(columnFamilyHandle);
            it.seek(startKey);
            this.constructor = constructor;
            this.scope = scope;
            this.it = it;
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
            it.next();
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
            for (Capture capture : rawQuery(aliasSurt, null)) {
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
