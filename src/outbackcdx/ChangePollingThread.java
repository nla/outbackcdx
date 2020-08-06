package outbackcdx;


import static outbackcdx.Json.GSON;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.Base64;
import java.util.Date;

import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

public class ChangePollingThread extends Thread {
    final byte[] SEQ_NUM_KEY = "#ReplicationSequence".getBytes();

    String primaryReplicationUrl = null;
    int pollingInterval = 10;
    DataStore dataStore = null;
    Index index = null;
    String since = "0";
    String finalUrl = null;
    String collection;
    boolean shuttingDown = false;
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
                }
            }
        });
    }

    public void run() {
        while (!shuttingDown) {
            try {
                long startTime = System.currentTimeMillis();
                try {
                    byte[] output = this.index.db.get(SEQ_NUM_KEY);
                    if(output == null){
                        since = "0";
                    } else {
                        since = new String(output);
                    }
                } catch (RocksDBException e) {
                    System.err.println(new Date() + " " + getName() + ": Received rocks db exception while looking up the value of the key " + new String(SEQ_NUM_KEY) + " locally");
                    e.printStackTrace();
                }
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

                long sleepTime = (pollingInterval * 1000) - (System.currentTimeMillis() - startTime);
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

    private void replicate() throws IOException, RocksDBException {
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
        long sequenceNumber = 0;
        String writeBatch = null;
        System.out.println(new Date() + " " + getName() + ": requesting replication from " + finalUrl);
        HttpResponse response = httpclient.execute(request);

        if(response.getStatusLine().getStatusCode() != 200){
            InputStream inputStream = response.getEntity().getContent();
            String contentString = new BufferedReader(new InputStreamReader(inputStream)).readLine();
            throw new IOException("Received '" + response.getStatusLine() + "' response from " + finalUrl +": \n" + contentString);
        }
        InputStream content = response.getEntity().getContent();
        JsonReader reader = GSON.newJsonReader(new InputStreamReader(content));
        reader.beginArray();
        while (reader.peek() != JsonToken.END_DOCUMENT && !shuttingDown) {
            JsonToken nextToken = reader.peek();
            if (JsonToken.BEGIN_OBJECT.equals(nextToken)) {
                reader.beginObject();
            } else if(JsonToken.NAME.equals(nextToken)){
                String name  =  reader.nextName();
                if(name.equals("sequenceNumber")){
                    sequenceNumber = Long.valueOf(reader.nextString());
                } else if (name.equals("writeBatch")){
                    writeBatch = reader.nextString();
                }
            } else if (JsonToken.END_OBJECT.equals(nextToken)){
                reader.endObject();
                assert writeBatch != null;
                commitWriteBatch(index, sequenceNumber, writeBatch);
                if (firstCommitted == null) {
                    firstCommitted = sequenceNumber;
                }
                lastCommitted = sequenceNumber;
                countCommitted++;
                totalLengthCommitted += writeBatch.length();
            } else if (JsonToken.END_ARRAY.equals(nextToken)){
                reader.endArray();
            }
        }

        String elapsed = String.format("%.3f", 1.0 * (System.currentTimeMillis() - start) / 1000);
        System.out.println(new Date() + " " + getName() + ": replicated "
                + countCommitted + " write batches (" + firstCommitted + ".."
                + lastCommitted + ") with total length " + totalLengthCommitted
                + " in " + elapsed + "s from " + finalUrl + " and our latest"
                + " sequence number is now " + index.getLatestSequenceNumber());
    }

    private void commitWriteBatch(Index index, long sequenceNumber, String writeBatch) throws RocksDBException {
        Base64.Decoder decoder = Base64.getDecoder();
        byte[] decodedWriteBatch = decoder.decode(writeBatch);
        try (WriteBatch batch = new WriteBatch(decodedWriteBatch)){
            batch.put(SEQ_NUM_KEY, String.valueOf(sequenceNumber).getBytes("ASCII"));
            index.commitBatch(batch);
        } catch (UnsupportedEncodingException e){
            throw new RuntimeException(e); // ASCII is everywhere; this shouldn't happen.
        }
    }
}
