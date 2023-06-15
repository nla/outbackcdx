package outbackcdx;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;

import org.rocksdb.*;

/**
 * Wraps RocksDB with a higher-level query interface.
 */
public class Index {
    final String name;
    final RocksDB db;
    final ColumnFamilyHandle defaultCF;
    final ColumnFamilyHandle aliasCF;
    final AccessControl accessControl;
    final long scanCap;
    final UrlCanonicalizer canonicalizer;
    private Thread upgradeThread;
    private Thread compactThread;

    public Index(String name, RocksDB db, ColumnFamilyHandle defaultCF, ColumnFamilyHandle aliasCF, AccessControl accessControl) {
        this(name, db, defaultCF, aliasCF, accessControl, Long.MAX_VALUE, new UrlCanonicalizer());
    }

    public Index(String name, RocksDB db, ColumnFamilyHandle defaultCF, ColumnFamilyHandle aliasCF, AccessControl accessControl, long scanCap, UrlCanonicalizer canonicalizer) {
        this.name = name;
        this.db = db;
        this.defaultCF = defaultCF;
        this.aliasCF = aliasCF;
        this.accessControl = accessControl;
        this.scanCap = scanCap;
        this.canonicalizer = canonicalizer;
    }

    public void flushWal() throws RocksDBException{
        this.db.flushWal(true);
    }

    public TransactionLogIterator getUpdatesSince(long sequenceNumber) throws RocksDBException {
        TransactionLogIterator logReader = db.getUpdatesSince(sequenceNumber);
        return logReader;
    }

    public long getLatestSequenceNumber() {
        return db.getLatestSequenceNumber();
    }

    /**
     * Returns all captures that match the given prefix.
     */
    public CloseableIterator<Capture> prefixQuery(String surtPrefix, Predicate<Capture> filter) {
        return filteredCaptures(Capture.encodeKeyV0(surtPrefix, 0), record -> record.urlkey.startsWith(surtPrefix), filter, false);
    }

    public CloseableIterator<Capture> prefixQueryAP(String surtPrefix, String accessPoint) {
        if (accessPoint != null && accessControl != null) {
            return prefixQuery(surtPrefix, accessControl.filter(accessPoint, new Date()));
        } else {
            return prefixQuery(surtPrefix, null);
        }
    }

    /**
     * Returns all captures with keys in the given range.
     */
    public CloseableIterator<Capture> rangeQuery(String startSurt, String endSurt, Predicate<Capture> filter) {
        return filteredCaptures(Capture.encodeKeyV0(startSurt, 0), record -> record.urlkey.compareTo(endSurt) < 0, filter, false);
    }

    /**
     * Returns all captures for the given url.
     */
    public CloseableIterator<Capture> query(String surt, Predicate<Capture> filter) {
        return query(surt, Query.MIN_TIMESTAMP, Query.MAX_TIMESTAMP, filter);
    }

    public CloseableIterator<Capture> query(String surt, long from, long to, Predicate<Capture> filter) {
        String urlkey = resolveAlias(surt);
        byte[] key = Capture.encodeKeyV0(urlkey, from);
        return filteredCaptures(key, record -> record.urlkey.equals(urlkey) && record.timestamp <= to, filter, false);
    }

    /**
     * Returns all captures for the given url.
     */
    public CloseableIterator<Capture> queryAP(String surt, String accessPoint) {
        if (accessPoint != null && accessControl != null) {
            return query(surt, accessControl.filter(accessPoint, new Date()));
        } else {
            return query(surt, null);
        }
    }

    /**
     * Returns all captures for the given url in reverse order.
     */
    public CloseableIterator<Capture> reverseQuery(String surt, Predicate<Capture> filter) {
        return reverseQuery(surt, Query.MIN_TIMESTAMP, Query.MAX_TIMESTAMP, filter);
    }

    public CloseableIterator<Capture> reverseQuery(String surt, long from, long to, Predicate<Capture> filter) {
        String urlkey = resolveAlias(surt);
        byte[] key = Capture.encodeKeyV0(urlkey, to);
        return filteredCaptures(key, record -> record.urlkey.equals(urlkey) && record.timestamp >= from, filter, true);
    }

    /**
     * Returns all captures for the given url ordered by distance from the given timestamp.
     */
    public CloseableIterator<Capture> closestQuery(String surt, long targetTimestamp, Predicate<Capture> filter) {
        String urlkey = resolveAlias(surt);
        byte[] key = Capture.encodeKeyV0(urlkey, targetTimestamp);
        Predicate<Capture> scope = record -> record.urlkey.equals(urlkey);
        return new ClosestTimestampIterator(targetTimestamp,
                filteredCaptures(key, scope, filter, false),
                filteredCaptures(key, scope, filter, true));
    }

    public CloseableIterator<Capture> execute(Query query) {
        Predicate<Capture> filter = query.predicate;
        if (query.accessPoint != null && accessControl != null) {
            filter = filter.and(accessControl.filter(query.accessPoint, new Date()));
        }

        switch (query.matchType) {
            case EXACT:
                switch (query.sort) {
                    case DEFAULT:
                        return query(query.urlkey, query.from, query.to, filter);
                    case CLOSEST:
                        return closestQuery(query.urlkey, Long.parseLong(query.closest), filter);
                    case REVERSE:
                        return reverseQuery(query.urlkey, query.from, query.to, filter);
                }
            case PREFIX:
                if (query.url != null && query.url.endsWith("/") && !query.urlkey.endsWith("/")) {
                    query.urlkey += "/";
                }
                return prefixQuery(query.urlkey, filter);
            case HOST:
                return prefixQuery(hostFromSurt(query.urlkey) + ")/", filter);
            case DOMAIN:
                String host = hostFromSurt(query.urlkey);
                return rangeQuery(host, host + "-", filter);
            case RANGE:
                return rangeQuery(query.urlkey, "~", filter);
            default:
                throw new IllegalArgumentException("unknown matchType: " + query.matchType);
        }
    }

    /**
     * "org,example)/foo/bar" => "org,example"
     */
    static String hostFromSurt(String surtPrefix) {
        int i = surtPrefix.indexOf(")/");
        return i < 0 ? surtPrefix : surtPrefix.substring(0, i);
    }

    public synchronized boolean compactInBackground() {
        if (compactThread != null && compactThread.isAlive()) return false;
        compactThread = new Thread(this::compact, "Index compaction (" + name + ")");
        compactThread.setDaemon(true);
        compactThread.start();
        return true;
    }

    void compact() {
        System.out.println("Compacting index '" + name + "'");
        long startTime = System.currentTimeMillis();
        try {
            db.compactRange(defaultCF);
        } catch (RocksDBException e) {
            e.printStackTrace();
        }
        System.out.println("Compaction complete (" + name + ") in " +
                Duration.ofMillis(System.currentTimeMillis() - startTime));
    }

    public synchronized boolean upgradeInBackground() {
        if (upgradeThread != null && upgradeThread.isAlive()) return false;
        upgradeThread = new Thread(this::upgrade, "Index upgrade (" + name + ")");
        upgradeThread.setDaemon(true);
        upgradeThread.start();
        return true;
    }

    void upgrade() {
        // So the basic idea is to iterate over all records and write them back
        // to the database with the new key format. This is done in batches of
        // to avoid running out of memory.
        int batchSize = 10000;
        int recordsInBatch = 0;
        long recordsSeen = 0;
        long recordsChanged = 0;
        long estimatedTotal = estimatedRecordCount();
        long startTime = System.currentTimeMillis();
        long lastProgressTime = startTime;
        long lastProgressRecords = 0;
        String lastSeenUrlKey = null;
        int targetVersion = FeatureFlags.indexVersion();

        System.out.println("Upgrading index '" + name + "' (~" + estimatedTotal + " records) to index version " + targetVersion);

        try (ReadOptions readOptions = new ReadOptions().setTailing(true);
                WriteOptions writeOptions = new WriteOptions();
                WriteBatch writeBatch = new WriteBatch();
                RocksIterator it = db.newIterator(defaultCF, readOptions)) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                byte[] oldKey = it.key();
                byte[] oldValue = it.value();
                Capture capture = new Capture(oldKey, oldValue);
                byte[] newKey = capture.encodeKey();
                byte[] newValue = capture.encodeValue();
                recordsSeen++;
                lastSeenUrlKey = capture.urlkey;
                if (!Arrays.equals(oldKey, newKey) || !Arrays.equals(oldValue, newValue)) {
                    // Found a record that needs to be upgraded.
                    writeBatch.delete(defaultCF, oldKey);
                    writeBatch.put(defaultCF, newKey, newValue);
                    recordsChanged++;
                    recordsInBatch++;
                    if (recordsInBatch >= batchSize) {
                        db.write(writeOptions, writeBatch);
                        writeBatch.clear();
                        recordsInBatch = 0;
                    }
                }
                if (recordsSeen % 16384 == 0) {
                    long now = System.currentTimeMillis();
                    if (now - lastProgressTime > 5000) {
                        // the estimated record count tends to change over time
                        // it seems particularly inaccurate after restart so we refresh it
                        estimatedTotal = estimatedRecordCount();
                        if (estimatedTotal == 0) estimatedTotal = 1; // guard against division by zero
                        long recordsPerSecond = (recordsSeen - lastProgressRecords) * 1000 / (now - lastProgressTime);
                        Duration eta = Duration.ofSeconds((now - startTime) * (estimatedTotal - recordsSeen) / recordsSeen / 1000);
                        double percentage = recordsSeen * 100.0 / estimatedTotal;
                        System.out.println("Upgrade progress (" + name + "): ~" +
                                String.format("%.2f", percentage) + "% " +
                                recordsSeen + "/~" + estimatedTotal
                                + " (" + recordsChanged + " changed) " + recordsPerSecond + "/s"
                                +  " ETA: " + eta);
                        lastProgressTime = now;
                    }
                }
            }

            if (recordsInBatch > 0) {
                db.write(writeOptions, writeBatch);
                writeBatch.clear();
            }
            Duration duration = Duration.ofMillis(System.currentTimeMillis() - startTime);
            System.out.println("Upgrade complete (" + name + "): " + recordsSeen + " records"
                    + " (" + recordsChanged + " changed) in " + duration);
        } catch (RocksDBException e) {
            System.err.println("Upgrade failed (" + name + ") at urlkey: " + lastSeenUrlKey);
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    /**
     * Combines a forward iterator and backward iterator into order-by closest
     * distance to timestamp iterator.
     */
    static class ClosestTimestampIterator implements CloseableIterator<Capture> {
        final long targetMillis;
        final CloseableIterator<Capture> forwardIterator;
        final CloseableIterator<Capture> backwardIterator;
        Capture nextForward = null;
        Capture nextBackward = null;

        ClosestTimestampIterator(long targetTimestamp, CloseableIterator<Capture> forwardIterator, CloseableIterator<Capture> backwardIterator) {
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

        @Override
        public void close() {
            forwardIterator.close();
            backwardIterator.close();
        }
    }

    /**
     * Perform a query without first resolving aliases.
     */
    private CloseableIterator<Capture> rawQuery(String key, Predicate<Capture> filter, boolean reverse) {
        return filteredCaptures(Capture.encodeKeyV0(key, 0), record -> record.urlkey.equals(key), filter, reverse);
    }

    /**
     * Returns all captures starting from the given key.
     */
    CloseableIterator<Capture> capturesAfter(String start) {
        return filteredCaptures(Capture.encodeKeyV0(start, 0), record -> true, null, false);
    }

    public String resolveAlias(String surt) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 32 && seen.add(surt); i++) {
            surt = resolveAliasOnce(surt);
        }
        return surt;
    }

    public String resolveAliasOnce(String surt) {
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

    private CloseableIterator<Capture> filteredCaptures(byte[] key, Predicate<Capture> scope, Predicate<Capture> filter, boolean reverse) {
        CloseableIterator<Capture> captures = new Records<>(db, defaultCF, key, Capture::new, scope, reverse, scanCap);
        if (filter != null) {
            captures = new FilteringIterator<>(captures, filter);
        }
        return captures;
    }

    public Iterable<Alias> listAliases(String start) {
        byte[] key = start.getBytes(US_ASCII);
        return () -> new Records<>(db, aliasCF, key, Alias::new, (alias) -> true, false, scanCap);
    }

    public long estimatedRecordCount() {
        try {
            return db.getLongProperty(defaultCF, "rocksdb.estimate-num-keys");
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    private interface RecordConstructor<T> {
        T construct(byte[] key, byte[] value);
    }

    /**
     * Iterates capture records in RocksDb, starting from queryUrl and continuing until scope returns false.
     */
    private static class Records<T> implements CloseableIterator<T> {
        private final RocksIterator it;
        private final Predicate<T> scope;
        private final RecordConstructor<T> constructor;
        private T record = null;
        private long cap;
        private long count = 0;
        private final boolean reverse;
        private boolean exhausted = false;
        private boolean closed;

        public Records(RocksDB db, ColumnFamilyHandle columnFamilyHandle, byte[] startKey, RecordConstructor<T> constructor, Predicate<T> scope, boolean reverse, long cap) {
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
            this.cap = cap;
        }

        public boolean hasNext() {
            if (closed) throw new IllegalStateException("Iterator is closed");
            if (exhausted) {
                return false;
            }
            if (record == null && it.isValid()) {
                record = constructor.construct(it.key(), it.value());
            }
            if (record == null || !scope.test(record) || count >= cap) {
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
            count += 1;
            return record;
        }

        @Override
        public void close() {
            if (!closed && !exhausted) it.close();
            closed = true;
        }
    }

    /**
     * Wraps another iterator and only returns elements that match the given predicate.
     */
    private static class FilteringIterator<T> implements CloseableIterator<T> {
        private final CloseableIterator<T> iterator;
        private final Predicate<T> predicate;
        private T next;
        private boolean holdingNext = false;

        public FilteringIterator(CloseableIterator<T> iterator, Predicate<T> predicate) {
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

        @Override
        public void close() {
            iterator.close();
        }
    }

    public Batch beginUpdate() {
        return new Batch();
    }

    public void commitBatch(WriteBatch writeBatch) throws RocksDBException {
        try (WriteOptions options = new WriteOptions()) {
            options.setSync(true);
            db.write(options, writeBatch);
        }
    }

    public class Batch implements AutoCloseable {
        private WriteBatch dbBatch = new WriteBatch();
        private final Map<String, String> newAliases = new HashMap<>();

        private Batch() {
        }

        /**
         * Add a new capture to the index.  Existing captures with the same urlkey and timestamp will be overwritten.
         * @throws IOException 
         */
        public void putCapture(Capture capture) throws IOException {
            String resolved = newAliases.get(capture.urlkey);
            if (resolved != null) {
                capture.urlkey = resolved;
            } else {
                capture.urlkey = resolveAlias(capture.urlkey);
            }
            try {
                dbBatch.put(capture.encodeKey(), capture.encodeValue());
            } catch (RocksDBException e) {
                throw new IOException(e);
            }
        }

        /**
         * Deletes a capture from the index. Does not actually check if the capture exists.
         * @throws IOException 
         */
        void deleteCapture(Capture capture) throws IOException {
            capture.urlkey = resolveAlias(capture.urlkey);
            try {
                dbBatch.delete(capture.encodeKey());
            } catch (RocksDBException e) {
                throw new IOException(e);
            }
        }

        /**
         * Adds a new alias to the index.  Updates existing captures affected by the new alias.
         * @throws IOException 
         */
        public void putAlias(String aliasSurt, String targetSurt) throws IOException {
            if (aliasSurt.equals(targetSurt)) {
                return; // a self-referential alias is equivalent to no alias so don't bother storing it
            }
            try {
                dbBatch.put(aliasCF, aliasSurt.getBytes(US_ASCII), targetSurt.getBytes(US_ASCII));
            } catch (RocksDBException e) {
                throw new IOException(e);
            }
            newAliases.put(aliasSurt, targetSurt);
            updateExistingRecordsWithNewAlias(dbBatch, aliasSurt, targetSurt);
        }

        /**
         * Removes an alias from the index.  Updates existing captures affected by the removed alias.
         */
        public void deleteAlias(String aliasSurt) throws IOException {
            String targetSurt = resolveAlias(aliasSurt);
            if (targetSurt.equals(aliasSurt)) return; // no such alias

            try {
                dbBatch.delete(aliasCF, aliasSurt.getBytes(US_ASCII));

                // rekey records that were affected by the alias
                // FIXME: can race with newly inserted records
                try (CloseableIterator<Capture> it = rawQuery(targetSurt, null, false)) {
                    while (it.hasNext()) {
                        Capture capture = it.next();
                        String surt = canonicalizer.surtCanonicalize(capture.original);
                        if (surt.equals(aliasSurt) && !surt.equals(capture.urlkey)) {
                            dbBatch.delete(capture.encodeKey());
                            capture.urlkey = surt;
                            dbBatch.put(capture.encodeKey(), capture.encodeValue());
                        }
                    }
                }
            } catch (RocksDBException e) {
                throw new IOException(e);
            }
        }

        public void commit() throws IOException {
            try {
                commitBatch(dbBatch);
            } catch (RocksDBException e) {
                throw new RuntimeException(e);
            }

            /*
             * Most of the time this will do nothing as we've already updated existing captures in putAlias, but there's
             * a data race as another batch could have inserted some captures in bewtween putAlias() and commit().
             *
             * Rather than serializing updates let's just do a second pass after committing to catch any captures that
             * were added in the meantime.
             */
            updateExistingRecordsWithNewAliases();
        }

        private void updateExistingRecordsWithNewAliases() throws IOException {
            try (WriteBatch wb = new WriteBatch()) {
                for (Map.Entry<String, String> entry : newAliases.entrySet()) {
                    updateExistingRecordsWithNewAlias(wb, entry.getKey(), entry.getValue());
                }
                try {
                    commitBatch(wb);
                } catch (RocksDBException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        private void updateExistingRecordsWithNewAlias(WriteBatch wb, String aliasSurt, String targetSurt) throws IOException {
            try (CloseableIterator<Capture> it = rawQuery(aliasSurt, null, false)) {
                while (it.hasNext()) {
                    Capture capture = it.next();
                    try {
                        wb.delete(capture.encodeKey());
                    } catch (RocksDBException e) {
                        throw new IOException(e);
                    }
                    capture.urlkey = targetSurt;
                    try {
                        wb.put(capture.encodeKey(), capture.encodeValue());
                    } catch (RocksDBException e) {
                        throw new IOException(e);
                    }
                }
            }
        }

        @Override
        public void close() {
            dbBatch.close();
        }
    }
}
