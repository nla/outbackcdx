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
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
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

    protected ChangePollingThread(String primaryReplicationUrl, int pollingInterval, DataStore dataStore) throws IOException {
        this.pollingInterval = pollingInterval;
        this.dataStore = dataStore;
        this.primaryReplicationUrl = primaryReplicationUrl.replaceFirst("/$", "");
        String[] splitCollectionUrl = this.primaryReplicationUrl.split("/");
        collection = splitCollectionUrl[splitCollectionUrl.length - 1];
        this.index = dataStore.getIndex(collection, true);
    }

    public void run() {
        while (true) {
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
                    System.err.println("Received rocks db exception while looking up the value of the key " + new String(SEQ_NUM_KEY) + " locally");
                    e.printStackTrace();
                }
                finalUrl = primaryReplicationUrl + "/changes?n=50&since=" + since;
                try {
                    replicate();
                } catch (IOException e) {
                    System.err.println(new Date() + " ChangePollingThread: I/O exception processing " + finalUrl);
                    e.printStackTrace();
                } catch (RocksDBException e){
                    System.err.println(new Date() + " ChangePollingThread: The plane has crashed into the mountain. RocksDB threw an exception during replication from "+ finalUrl);
                    e.printStackTrace();
                } catch (Exception e) {
                    System.err.println(new Date() + " ChangePollingThread: Dang! something happened while processing " + finalUrl);
                    e.printStackTrace();
                }

                long sleepTime = (pollingInterval * 1000) - (System.currentTimeMillis() - startTime);
                if (sleepTime > 0) {
                    try {
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException e) {
                        System.out.println("Received interruption at " + System.currentTimeMillis());
                        return;
                    }
                }
            } catch (Exception e) {
                System.err.println("proceeding after unexpected exception");
                e.printStackTrace();
            }
        }
    }

    private void replicate() throws IOException, RocksDBException {
        // strip trailing slash if necessary
        CloseableHttpClient httpclient = HttpClients.createDefault();
        HttpGet request = new HttpGet(finalUrl);
        long sequenceNumber = 0;
        String writeBatch = null;
        System.out.println(new Date() + " requesting " + finalUrl);
        HttpResponse response = httpclient.execute(request);
        System.out.println(new Date() + " received " + response.getStatusLine() + " from " + finalUrl);

        if(response.getStatusLine().getStatusCode() != 200){
            InputStream inputStream = response.getEntity().getContent();
            String contentString = new BufferedReader(new InputStreamReader(inputStream)).readLine();
            throw new IOException("Received '" + response.getStatusLine() + "' response from " + finalUrl +": \n" + contentString);
        }
        InputStream content = response.getEntity().getContent();
        JsonReader reader = GSON.newJsonReader(new InputStreamReader(content));
        reader.beginArray();
        while (reader.peek() != JsonToken.END_DOCUMENT) {
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
            } else if (JsonToken.END_ARRAY.equals(nextToken)){
                reader.endArray();
            }
        }
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
        System.out.println(new Date() + " ChangePollingThread: Committed Write Batch number "+sequenceNumber+" with length "+writeBatch.length());
    }
}
