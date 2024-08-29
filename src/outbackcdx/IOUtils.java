package outbackcdx;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

class IOUtils {
    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        while (true) {
            int n = in.read(buffer);
            if (n < 0) break;
            out.write(buffer, 0, n);
        }
    }
}
