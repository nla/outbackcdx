package outbackcdx;


import java.io.*;
import java.util.Base64;
import java.util.HashMap;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;

import static outbackcdx.Json.GSON;


public class ChangePollingThread extends Thread {
    final byte[] SEQ_NUM_KEY = "#ReplicationSequence".getBytes();

    String primaryReplicationUrl = null;
    int pollingInterval = 10;
    DataStore dataStore = null;
    Index index = null;
    Long sequenceNumber = Long.valueOf(0);

    ChangePollingThread(String primaryReplicationUrl, int pollingInterval, DataStore dataStore) throws IOException {
        this.primaryReplicationUrl = primaryReplicationUrl;
        this.pollingInterval = pollingInterval;
        this.dataStore = dataStore;
        this.primaryReplicationUrl = this.primaryReplicationUrl.replaceFirst("/$", "");
        String[] splitCollectionUrl = this.primaryReplicationUrl.split("/");
        String collection = splitCollectionUrl[splitCollectionUrl.length - 1];
        this.index = dataStore.getIndex(collection, true);

        try {
            byte[] output = this.index.db.get(SEQ_NUM_KEY);
            String sequence = output.toString();
            sequenceNumber = Long.valueOf(sequence);
        } catch (RocksDBException e) {
            System.out.println("Received rocks db exception while looking up the value of the key " + SEQ_NUM_KEY + " locally");
            e.printStackTrace();
        }
        this.primaryReplicationUrl = primaryReplicationUrl + "/changes?since=" + sequenceNumber;
    }

    public void run() {
        while (true) {
            long startTime = System.currentTimeMillis();
            try {
                replicate();
            } catch (IOException e) {
                System.out.println("Received I/O exception while processing " + primaryReplicationUrl);
                e.printStackTrace();
            } catch (RocksDBException e){
                System.out.println("The plane has crashed into the mountain. RocksDB threw an exception during replication from "+ primaryReplicationUrl);
                e.printStackTrace();
            } catch (Exception e) {
                System.out.println("Dang! something happened while processing " + primaryReplicationUrl);
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
        }
    }

    private void replicate() throws IOException, RocksDBException {
        // strip trailing slash if necessary
        CloseableHttpClient httpclient = HttpClients.createDefault();
        HttpGet request = new HttpGet(primaryReplicationUrl);
        long sequenceNumber = 0;
        String writeBatch = null;
        HttpResponse response = httpclient.execute(request);

        if(response.getStatusLine().getStatusCode() != 200){
            InputStream inputStream = response.getEntity().getContent();
            String contentString = new BufferedReader(new InputStreamReader(inputStream)).readLine();
            throw new IOException("Received '" + response.getStatusLine() + "' response from " + primaryReplicationUrl + ": \n" + contentString);
        }
        InputStream content = response.getEntity().getContent();
        JsonReader reader = GSON.newJsonReader(new InputStreamReader(content));
        reader.beginArray();
        while (reader.hasNext()) {
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
                commitWriteBatch(index, sequenceNumber, writeBatch);
            }
        }
    }

    private void commitWriteBatch(Index index, long sequenceNumber, String writeBatch) throws RocksDBException {
        Base64.Decoder decoder = Base64.getDecoder();
        byte[] decodedWriteBatch = decoder.decode(writeBatch);
        WriteBatch batch = new WriteBatch(decodedWriteBatch);
        try {
            batch.put(SEQ_NUM_KEY, String.valueOf(sequenceNumber).getBytes("ASCII"));
        } catch (UnsupportedEncodingException e){
            throw new RuntimeException(e); // ASCII is everywhere; this shouldn't happen.
        }
        index.commitBatch(batch);
    }
}
