package outbackcdx;

import org.netpreserve.jwarc.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;

import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
import static outbackcdx.Web.Status.*;

public class Replay {
    private static final Set<String> X_ARCHIVE_ORIG_HEADERS = Set.of("access-control-allow-origin",
            "age", "alt-svc", "cache-control", "cookie",
            "connection", "content-md5", "content-security-policy",
            "content-security-policy-report-only", "date", "etag", "last-modified", "memento-datetime",
            "p3p", "pragma", "public-key-pins", "retry-after", "server", "status", "strict-transport-security",
            "trailer", "tk", "upgrade", "upgrade-insecure-requests", "vary", "via", "warning", "x-frame-options",
            "x-xss-protection");
    private final String warcBaseUrl;

    public Replay(String warcBaseUrl) {
        this.warcBaseUrl = warcBaseUrl;
    }

    public Web.Response replay(Index index, String date, String url, String modifier, Web.Request request) throws IOException {
        if (modifier.equals("id_")) {
            return replayIdentity(index, date, url, request);
        } else if (modifier.isEmpty()) {
            return new Web.Response(200, "text/html", "<!doctype html><body><script src=/replay.js></script>");
        } else {
            throw new IllegalArgumentException("modifier must be either id_ or empty");
        }
    }

    public Web.Response replayIdentity(Index index, String date, String url, Web.Request request) throws IOException {
        Capture capture = findClosestCapture(index, date, url);
        if (capture == null) return new Web.Response(NOT_FOUND, "text/plain", "Not in archive");

        try (WarcReader warcReader = openWarcFileAtPosition(capture.file, capture.compressedoffset, capture.length)) {
            warcReader.position(capture.compressedoffset);
            WarcRecord record = warcReader.next().orElse(null);
            if (record == null) throw new IOException("Missing WARC record");

            OffsetDateTime captureDate = record.date().atOffset(ZoneOffset.UTC);
            MultiMap<String, String> headers = new MultiMap<>();
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Memento-Datetime", RFC_1123_DATE_TIME.format(captureDate));
            if (record instanceof WarcResponse) {
                HttpResponse http = ((WarcResponse) record).http();
                http.headers().map().forEach((name, values) -> {
                    if (X_ARCHIVE_ORIG_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                        name = "X-Archive-Orig-" + name;
                    }
                    for (String value : values) {
                        headers.add(name, value);
                    }
                });
                try (OutputStream out = request.streamResponse(http.status(), headers)) {
                    http.body().stream().transferTo(out);
                }
            } else if (record instanceof WarcResource) {
                WarcResource resource = (WarcResource) record;
                resource.headers().sole("Content-Type").ifPresent(value -> headers.add("Content-Type", value));
                try (OutputStream out = request.streamResponse(OK, headers)) {
                    resource.body().stream().transferTo(out);
                }
            } else {
                throw new IOException("Unexpected WARC record type: " + record.getClass());
            }
            return Web.Response.ALREADY_SENT;
        }
    }

    private  WarcReader openWarcFileAtPosition(String filename, long position, Long length) throws IOException {
        if (filename.contains("../")) {
            throw new IllegalArgumentException("Refusing to open filename containing ../");
        }

        URI warcUrl = URI.create(warcBaseUrl + filename);
        if (warcUrl.getScheme().equals("file")) {
            var warcReader = new WarcReader(Path.of(warcUrl.getPath()));
            try {
                warcReader.position(position);
                return warcReader;
            } catch (Throwable e) {
                warcReader.close();
                throw e;
            }
        } else {
            HttpURLConnection connection = (HttpURLConnection)warcUrl.toURL().openConnection();
            String end = length != null && length > 0 ? String.valueOf(position + length - 1) : "";
            connection.addRequestProperty("Range", "bytes=" + position + "-" + end);
            return new WarcReader(connection.getInputStream());
        }

    }

    private static Capture findClosestCapture(Index index, String date, String url) {
        Query query = new Query(new MultiMap<>(), null);
        query.url = url;
        query.sort = Query.Sort.CLOSEST;
        query.closest = date;

        try (CloseableIterator<Capture> captures = query.execute(index)) {
            if (!captures.hasNext()) return null;
            return captures.next();
        }
    }
}
