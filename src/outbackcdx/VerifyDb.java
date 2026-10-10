package outbackcdx;

import com.moaduda.onggi.Options;
import com.moaduda.onggi.Store;
import com.moaduda.onggi.StoreException;

import java.nio.file.Path;

/**
 * Standalone CLI probe that opens an Onggi store at the given path and exits. Intended for use as a corruption check from
 * shell scripts (e.g. systemd ExecStartPre on a replica) where we want
 * the same open semantics OutbackCDX uses: the manifest is replayed, every
 * table it lists is opened and checked, and the WAL is replayed.
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
        try (Store store = Store.open(Path.of(path), Options.defaults().withCreateIfMissing(false))) {
            System.exit(0);
        } catch (StoreException e) {
            System.err.println("VerifyDb: open failed for " + path + ": " + e.getMessage());
            System.exit(1);
        }
    }
}
