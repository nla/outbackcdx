package outbackcdx;

import org.apache.commons.collections4.iterators.PeekingIterator;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.BufferedOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.logging.Logger;

/**
 * Implements the Wayback RemoteCollection XmlQuery interface.
 */
public class XmlQuery {
    final static Logger log = Logger.getLogger(XmlQuery.class.getName());
    final static String DEFAULT_ENCODING = "UTF-8";

    final Index index;
    final String accessPoint;
    String queryUrl;
    final long offset;
    final long limit;
    final String queryType;
    final Long queryDate;
    private final long maxNumResults;

    public XmlQuery(Web.Request request, Index index, Iterable<FilterPlugin> filterPlugins, UrlCanonicalizer canonicalizer, long maxNumResults) {
        this.index = index;
        this.maxNumResults = maxNumResults;

        Map<String, String> params = request.params();
        Map<String, String> query = decodeQueryString(params.get("q"));

        accessPoint = params.get("accesspoint");
        queryType = query.getOrDefault("type", "urlquery").toLowerCase();
        queryUrl = canonicalizer.surtCanonicalize(query.get("url"));
        queryDate = query.containsKey("date") ? Long.parseLong(query.get("date")) : null;

        String countParam = params.get("count");
        if (countParam != null) {
            limit = Long.parseLong(countParam);
        } else {
            // deprecated
            limit = Long.parseLong(query.getOrDefault("limit", "10000"));
        }

        String startPageParam = params.get("start_page");
        if (startPageParam != null) {
            long startPage = Long.parseLong(startPageParam);
            if (startPage < 1) {
                startPage = 1;
            }
            offset = limit * (startPage - 1);
        } else {
            // deprecated
            offset = Long.parseLong(query.getOrDefault("offset", "0"));
        }
    }

    private static Map<String,String> decodeQueryString(String q) {
        try {
            Map<String,String> m = new HashMap<>();
            for (String entry : q.split(" ")) {
                String[] fields = entry.split(":", 2);
                // we use URLDecoder rather than URLCanonicalize here to match Wayback's encoding behaviour
                // (spaces encoded as + rather than %20)
                String key = URLDecoder.decode(fields[0], "UTF-8");
                String value = URLDecoder.decode(fields[1], "UTF-8");
                m.put(key, value);
            }
            return m;
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private static void writeElement(XMLStreamWriter out, String name, Object value) throws XMLStreamException {
        out.writeStartElement(name);
        out.writeCharacters(value.toString());
        out.writeEndElement();
    }

    public NanoHTTPD.Response streamResults() {
        return new NanoHTTPD.Response(NanoHTTPD.Response.Status.OK, "application/xml;charset=" + DEFAULT_ENCODING, outputStream -> {
            try {
                XMLOutputFactory factory = XMLOutputFactory.newInstance();
                XMLStreamWriter out = factory.createXMLStreamWriter(new BufferedOutputStream(outputStream), DEFAULT_ENCODING);
                out.writeStartDocument(DEFAULT_ENCODING, "1.0");
                out.writeStartElement("wayback");

                switch (queryType) {
                    case "urlquery":
                        urlQuery(out);
                        break;
                    case "prefixquery":
                        prefixQuery(out);
                        break;
                    default:
                        out.writeStartElement("error");
                        writeElement(out, "title", "Unsupported query type");
                        writeElement(out, "message", "The requested query type is unknown to the index.");
                        out.writeEndElement();
                        break;
                }

                out.writeEndElement(); // </wayback>
                out.writeEndDocument();
                out.flush();
            } catch (XMLStreamException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void urlQuery(XMLStreamWriter out) throws XMLStreamException {
        boolean wroteHeader = false;
        long numReturned = 0;
        long numResults = 0;
        boolean scanningForClosestDate = queryDate != null;

        try (CloseableIterator<Capture> captures = index.queryAP(queryUrl, accessPoint)) {
            PeekingIterator<Capture> iterator = new PeekingIterator<>(captures);
            while (iterator.hasNext()) {
                Capture capture = iterator.next();
                if (numResults < offset) { // skip matches before offset
                    numResults++;
                    continue;
                } else if (numResults >= offset + limit) {
                    if (numResults < maxNumResults) { // count matches after limit up to maxNumResults
                        numResults++;
                        continue;
                    } else {
                        break;
                    }
                }
                numResults++;

                if (!wroteHeader) {
                    out.writeStartElement("results");
                    wroteHeader = true;
                }
                out.writeStartElement("result");
                writeElement(out, "compressedoffset", capture.compressedoffset);
                if (capture.length != -1) {
                    writeElement(out, "compressedendoffset", capture.length);
                }
                writeElement(out, "mimetype", capture.mimetype);
                writeElement(out, "file", capture.file);
                writeElement(out, "redirecturl", capture.redirecturl);
                writeElement(out, "urlkey", capture.urlkey);
                writeElement(out, "digest", capture.digest);
                writeElement(out, "httpresponsecode", capture.status);
                writeElement(out, "robotflags", capture.robotflags);
                writeElement(out, "url", capture.original);
                writeElement(out, "capturedate", capture.timestamp);

                // if the query includes a date annotate the closest capture to that date
                // since we scan the captures in date order we just have to wait until the next capture is further away
                // from the query date than the current one, annotate it and then stop
                if (scanningForClosestDate) {
                    Capture next = iterator.peek();
                    if (next == null || Math.abs(queryDate - capture.timestamp) < Math.abs(queryDate - next.timestamp)) {
                        writeElement(out, "closest", "true");
                        scanningForClosestDate = false;
                    }
                }

                out.writeEndElement(); // </result>
                numReturned++;
            }
        }

        if (wroteHeader) {
            out.writeEndElement(); // </results>
            writeRequestElement(out, "resultstypecapture", numReturned, numResults);
        } else if ("1".equals(System.getenv("CDX_PLUS_WORKAROUND")) && queryUrl.contains("%20")) {
            /*
             * XXX: NLA has a bunch of bad WARC files that contain + instead of %20 in the URLs. This is a dirty
             * workaround until we can fix them. If we found no results try again with + in place of %20.
             */
            queryUrl = queryUrl.replaceAll("%20", "+");
            urlQuery(out);
            return;
        } else {
            writeNotFoundError(out);
        }

        log.fine("[" + numReturned + "/" + numResults + " results] " + queryUrl);
    }

    private void prefixQuery(XMLStreamWriter out) throws XMLStreamException {
        boolean wroteHeader = false;
        long numResults = 0;
        long numReturned = 0;
        try (Resources it = new Resources(index.prefixQueryAP(queryUrl, accessPoint))) {
            while (it.hasNext()) {
                Resource resource = it.next();
                if (numResults < offset) {
                    numResults++;
                    continue;
                } else if (numResults >= offset + limit) {
                    if (numResults < maxNumResults) { // count matches after limit up to maxNumResults
                        numResults++;
                        continue;
                    } else {
                        break;
                    }
                }
                numResults++;

                if (!wroteHeader) {
                    out.writeStartElement("results");
                    wroteHeader = true;
                }
                out.writeStartElement("result");
                writeElement(out, "urlkey", resource.lastCapture.urlkey);
                writeElement(out, "originalurl", resource.lastCapture.original);
                writeElement(out, "numversions", resource.versions);
                writeElement(out, "numcaptures", resource.captures);
                writeElement(out, "firstcapturets", resource.firstCapture.timestamp);
                writeElement(out, "lastcapturets", resource.lastCapture.timestamp);
                out.writeEndElement(); // </result>
                numReturned++;
            }
        }

        if (wroteHeader) {
            out.writeEndElement(); // </results>
            writeRequestElement(out, "resultstypeurl", numReturned, numResults);
        } else {
            writeNotFoundError(out);
        }
    }

    private void writeRequestElement(XMLStreamWriter out, String resultsType, long numReturned, long numResults) throws XMLStreamException {
        out.writeStartElement("request");
        writeElement(out, "startdate", "19960101000000");
        writeElement(out, "enddate", Capture.arcTimeFormat.format(LocalDateTime.now(ZoneOffset.UTC)));
        writeElement(out, "type", queryType);
        writeElement(out, "firstreturned", offset);
        writeElement(out, "url", queryUrl);
        writeElement(out, "resultsrequested", limit);
        writeElement(out, "resultstype", resultsType);
        writeElement(out, "numreturned", numReturned);
        writeElement(out, "numresults", numResults);
        out.writeEndElement(); // </request>
    }

    private static void writeNotFoundError(XMLStreamWriter out) throws XMLStreamException {
        out.writeStartElement("error");
        writeElement(out, "title", "Resource Not In Archive");
        writeElement(out, "message", "The Resource you requested is not in this archive.");
        out.writeEndElement();
    }

    /**
     * Groups together all captures of the same URL.
     */
    private static class Resources implements CloseableIterator<Resource> {
        private final CloseableIterator<Capture> captures;
        private Capture capture = null;

        Resources(CloseableIterator<Capture> captures) {
            this.captures = captures;
        }

        public boolean hasNext() {
            return capture != null || captures.hasNext();
        }

        public Resource next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Resource result = new Resource();
            String previousDigest = null;
            if (capture == null) {
                capture = captures.next();
            }
            result.firstCapture = capture;
            result.lastCapture = capture;
            while (capture.urlkey.equals(result.firstCapture.urlkey)) {
                if (previousDigest == null || !previousDigest.equals(capture.digest)) {
                    result.versions++;
                    previousDigest = capture.digest;
                }
                result.captures++;
                result.lastCapture = capture;
                if (!captures.hasNext()) {
                    capture = null;
                    break;
                }
                capture = captures.next();
            }

            return result;
        }

        @Override
        public void close() {
            captures.close();
        }
    }

    static class Resource {
        long captures;
        long versions;
        Capture firstCapture;
        Capture lastCapture;
    }
}
