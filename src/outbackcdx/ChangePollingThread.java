package outbackcdx;


import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.MappingIterator;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.OptionalLong;

import static outbackcdx.Json.JSON_MAPPER;

public class ChangePollingThread extends Thread {
    String primaryReplicationUrl = null;
    int pollingInterval = 10;
    DataStore dataStore = null;
    Index index = null;
    String since = "0";
    String finalUrl = null;
    String collection;
    boolean shuttingDown = false;
    boolean announcedBootstrap = false;
    long batchSize = 10*1024*1024;

    protected ChangePollingThread(String primaryReplicationUrl, int pollingInterval, long batchSize, DataStore dataStore) throws IOException {
        super("ChangePollingThread(" + primaryReplicationUrl + ")");
        this.pollingInterval = pollingInterval;
        this.dataStore = dataStore;
        this.primaryReplicationUrl = primaryReplicationUrl.replaceFirst("/$", "");
        String[] splitCollectionUrl = this.primaryReplicationUrl.split("/");
        collection = splitCollectionUrl[splitCollectionUrl.length - 1];
        this.index = dataStore.getIndex(collection, true);
        this.batchSize = batchSize;

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                shuttingDown = true;
                try {
                    ChangePollingThread.this.join(60000);
                } catch (InterruptedException e) {
                    // ok
                }
            }
        });
    }

    public void run() {
        while (!shuttingDown) {
            try {
                long startTime = System.currentTimeMillis();
                since = null;
                try {
                    since = resolveSince();
                } catch (RocksDBException e) {
                    System.err.println(new Date() + " " + getName() + ": Received rocks db exception while looking up the value of the key " + new String(Index.REPLICATION_SEQUENCE_KEY) + " locally");
                    e.printStackTrace();
                }
                if (since != null) {
                    finalUrl = primaryReplicationUrl + "/changes?size=" + batchSize + "&since=" + since;
                    try {
                        if (!shuttingDown) {
                            replicate();
                        }
                    } catch (IOException e) {
                        System.err.println(new Date() + " " + getName() + ": I/O exception processing " + finalUrl);
                        e.printStackTrace();
                    } catch (RocksDBException e){
                        System.err.println(new Date() + " " + getName() + ": The plane has crashed into the mountain. RocksDB threw an exception during replication from "+ finalUrl);
                        e.printStackTrace();
                    } catch (Exception e) {
                        System.err.println(new Date() + " " + getName() + ": Dang! something happened while processing " + finalUrl);
                        e.printStackTrace();
                    }
                }

                long sleepTime = (pollingInterval * 1000L) - (System.currentTimeMillis() - startTime);
                if (sleepTime > 0 && !shuttingDown) {
                    try {
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException e) {
                        System.out.println(new Date() + " " + getName() + ": Received interruption at " + System.currentTimeMillis());
                        return;
                    }
                }
            } catch (Exception e) {
                System.err.println(new Date() + " " + getName() + ": proceeding after unexpected exception");
                e.printStackTrace();
            }
        }
        System.err.println(new Date() + " " + getName() + ": finished gracefully");

    }

    /**
     * The sequence to ask the primary for, or null to skip this poll.
     *
     * A collection holding data but no stored sequence can only have been copied
     * from the primary, by rsync or a checkpoint restore, so its sequence numbers
     * are the primary's and the next one it wants is its own latest plus one.
     * Adopting that lets copy-and-restart recovery finish without an operator
     * writing the key by hand.
     */
    String resolveSince() throws RocksDBException {
        byte[] stored = index.db.get(Index.REPLICATION_SEQUENCE_KEY);
        if (stored != null) {
            return new String(stored, StandardCharsets.US_ASCII);
        }

        long local = index.getLatestSequenceNumber();
        if (local == 0) {
            if (!announcedBootstrap) {
                announcedBootstrap = true;
                System.out.println(new Date() + " " + getName() + ": collection " + collection
                        + " is empty, bootstrapping from the oldest sequence the primary retains");
            }
            return "0";
        }

        OptionalLong primaryLatest = fetchPrimarySequence();
        if (!primaryLatest.isPresent()) {
            System.err.println(new Date() + " " + getName() + ": ERROR - cannot reach the primary to adopt"
                    + " a replication sequence for copied collection " + collection + "; will retry");
            return null;
        }

        long adopted = local + 1;
        // A copy of the primary cannot be ahead of it, so a higher sequence means
        // this data belongs to some other sequence space. Requesting it would land
        // past the primary's tail and read as caught up, stalling silently.
        if (adopted > primaryLatest.getAsLong() + 1) {
            System.err.println(new Date() + " " + getName() + ": ERROR - REPLICATION HALTED, local sequence "
                    + local + " for collection " + collection + " is beyond the primary's "
                    + primaryLatest.getAsLong() + ", so this data did not come from that primary."
                    + " Re-copy the collection, or set " + new String(Index.REPLICATION_SEQUENCE_KEY, StandardCharsets.US_ASCII)
                    + " to the sequence it was copied at.");
            return null;
        }

        index.db.put(Index.REPLICATION_SEQUENCE_KEY, String.valueOf(adopted).getBytes(StandardCharsets.US_ASCII));
        System.err.println(new Date() + " " + getName() + ": WARNING - collection " + collection + " holds data"
                + " but no replication sequence, so it was copied from the primary. Adopting " + adopted
                + " from local data. Records the copy itself was missing cannot be detected and will not be"
                + " replicated.");
        return String.valueOf(adopted);
    }

    private OptionalLong fetchPrimarySequence() {
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(10 * 1000)
                .setSocketTimeout(30 * 1000)
                .setConnectionRequestTimeout(5 * 1000).build();
        try (CloseableHttpClient client = HttpClientBuilder.create().setDefaultRequestConfig(config).build()) {
            HttpResponse response = client.execute(new HttpGet(primaryReplicationUrl + "/sequence"));
            if (response.getStatusLine().getStatusCode() != 200) {
                return OptionalLong.empty();
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                return line == null ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(line.trim()));
            }
        } catch (IOException | NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.PUBLIC_ONLY)
    public static class ChangeEvent {
        public long sequenceNumber;
        public byte[] writeBatch;
    }

    void replicate() throws IOException, RocksDBException {
        long start = System.currentTimeMillis();

        int countCommitted = 0;
        long totalLengthCommitted = 0;
        Long firstCommitted = null;
        Long lastCommitted = null;

        // timeouts in milliseconds
        RequestConfig config = RequestConfig.custom()
            .setConnectTimeout(10*1000)
            .setSocketTimeout(600*1000)
            .setConnectionRequestTimeout(5*1000).build();
        CloseableHttpClient httpclient =
            HttpClientBuilder.create().setDefaultRequestConfig(config).build();
        HttpGet request = new HttpGet(finalUrl);
        System.out.println(new Date() + " " + getName() + ": requesting replication from " + finalUrl);
        HttpResponse response = httpclient.execute(request);

        int statusCode = response.getStatusLine().getStatusCode();

        if (statusCode == 204) {
            /*
             * Our cursor is valid and the primary has nothing past it. Nothing
             * to apply and nothing to record.
             */
            return;
        }

        if (statusCode == 404) {
            /*
             * The primary has no such collection. Collections are created on
             * first write, so this can clear on its own once the
             * primary receives data -- keep polling.
             */
            System.err.println(new Date() + " " + getName()
                    + ": ERROR - collection " + collection + " does not exist on the primary; waiting for it to appear");
            return;
        }

        if (statusCode == 410) {
            /*
             * Our cursor has aged out of the primary's WAL retention window, so
             * the primary refuses to serve it rather than skipping the gap. This
             * needs manual intervention: a reseed from a checkpoint, or a reset of
             * #ReplicationSequence to an available sequence.
             */
            InputStream inputStream = response.getEntity().getContent();
            String contentString = new BufferedReader(new InputStreamReader(inputStream)).readLine();
            System.err.println(new Date() + " " + getName() + ": ERROR - REPLICATION STALLED, requested sequence not available: "
                    + contentString + " (requested " + finalUrl + ")");
            return;
        }
        if (statusCode != 200) {
            InputStream inputStream = response.getEntity().getContent();
            String contentString = new BufferedReader(new InputStreamReader(inputStream)).readLine();
            throw new IOException("Received '" + response.getStatusLine() + "' response from " + finalUrl +": \n" + contentString);
        }
        InputStream content = response.getEntity().getContent();

        try (MappingIterator<ChangeEvent> iterator = JSON_MAPPER.readerFor(ChangeEvent.class).readValues(content)) {
            while (iterator.hasNext()) {
                ChangeEvent item = iterator.next();
                assert item.writeBatch != null;
                commitWriteBatch(index, item.sequenceNumber, item.writeBatch);
                if (firstCommitted == null) {
                    firstCommitted = item.sequenceNumber;
                }
                lastCommitted = item.sequenceNumber;
                countCommitted++;
                totalLengthCommitted += item.writeBatch.length;
            }
        }

        String elapsed = String.format("%.3f", 1.0 * (System.currentTimeMillis() - start) / 1000);
        System.out.println(new Date() + " " + getName() + ": replicated "
                + countCommitted + " write batches (" + firstCommitted + ".."
                + lastCommitted + ") with total length " + totalLengthCommitted
                + " in " + elapsed + "s from " + finalUrl + " and our latest"
                + " sequence number is now " + index.getLatestSequenceNumber());
    }

    private void commitWriteBatch(Index index, long sequenceNumber, byte[] writeBatchData) throws RocksDBException {
        try (WriteBatch batch = new WriteBatch(writeBatchData)){
            // count() must be read before the marker put below, which would
            long nextSequenceNumber = sequenceNumber + batch.count();
            batch.put(Index.REPLICATION_SEQUENCE_KEY, String.valueOf(nextSequenceNumber).getBytes(StandardCharsets.US_ASCII));
            index.commitBatch(batch);
        }
    }
}
