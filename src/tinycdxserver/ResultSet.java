package tinycdxserver;

import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;

import java.io.Closeable;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

public class ResultSet implements Iterable<Record>, Closeable {
    final DBIterator iterator;
    boolean finished = false;
    Record record = null;
    private final String endKeyurl;
    private final long endTimestamp;

    public ResultSet(DB db, String startKeyurl, long startTimestamp, String endKeyurl, long endTimestamp) {
        iterator = db.iterator();
        iterator.seek(Record.encodeKey(startKeyurl, startTimestamp));
        this.endKeyurl = endKeyurl;
        this.endTimestamp = endTimestamp;
    }

    @Override
    public Iterator<Record> iterator() {
        return new Iterator<Record>() {
            @Override
            public boolean hasNext() {
                if (finished) {
                    return false;
                }
                if (record != null) {
                    return true;
                }
                if (iterator.hasNext()) {
                    Map.Entry<byte[], byte[]> entry = iterator.next();
                    record = new Record(entry.getKey(), entry.getValue());
                    int cmp = record.urlkey.compareTo(endKeyurl);
                    if (cmp < 0 || (cmp == 0 && record.timestamp <= endTimestamp)) {
                        return true;
                    }
                }
                close();
                return false;
            }

            @Override
            public Record next() {
                if (hasNext()) {
                    Record tmp = record;
                    record = null;
                    return tmp;
                }
                throw new NoSuchElementException();
            }
        };
    }

    @Override
    public void close() {
        finished = true;
        record = null;
        try {
            iterator.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
