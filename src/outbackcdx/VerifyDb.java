package outbackcdx;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Standalone CLI probe that opens a RocksDB at the given path with all of its
 * column families and exits. Intended for use as a corruption check from
 * shell scripts (e.g. systemd ExecStartPre on a replica) where we want
 * exactly the same open semantics OutbackCDX uses, not the weaker validation
 * that `ldb manifest_dump` provides.
 *
 * Exit codes:
 *   0  - DB opened successfully
 *   1  - DB failed to open (corrupt, missing, or unreadable)
 *   2  - bad arguments
 *
 * Invoke as:  java -cp outbackcdx.jar outbackcdx.VerifyDb /path/to/db
 */
public class VerifyDb {
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: VerifyDb <db-path>");
            System.exit(2);
        }
        String path = args[0];
        RocksDB.loadLibrary();

        List<byte[]> cfNames;
        try (Options listOpts = new Options()) {
            cfNames = RocksDB.listColumnFamilies(listOpts, path);
        } catch (RocksDBException e) {
            System.err.println("VerifyDb: listColumnFamilies failed for " + path + ": " + e.getMessage());
            System.exit(1);
            return;
        }
        if (cfNames.isEmpty()) {
            cfNames = Collections.singletonList(RocksDB.DEFAULT_COLUMN_FAMILY);
        }

        List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
        for (byte[] name : cfNames) {
            descriptors.add(new ColumnFamilyDescriptor(name));
        }
        List<ColumnFamilyHandle> handles = new ArrayList<>();

        try (DBOptions dbOpts = new DBOptions().setCreateIfMissing(false);
             RocksDB db = RocksDB.open(dbOpts, path, descriptors, handles)) {
            // Success — close handles cleanly.
            for (ColumnFamilyHandle h : handles) {
                h.close();
            }
            System.exit(0);
        } catch (RocksDBException e) {
            System.err.println("VerifyDb: open failed for " + path + ": " + e.getMessage());
            System.exit(1);
        }
    }
}
