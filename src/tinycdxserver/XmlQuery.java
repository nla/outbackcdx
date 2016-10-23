package tinycdxserver;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.BufferedOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Iterator;
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
    final String queryUrl;
    final long offset;
    final long limit;
    final String queryType;

    public XmlQuery(NanoHTTPD.IHTTPSession session, Index index) {
        this.index = index;

        Map<String, String> params = session.getParms();
        Map<String, String> query = decodeQueryString(params.get("q"));

        accessPoint = params.get("accesspoint");
        queryType = query.get("type").toLowerCase();
        queryUrl = UrlCanonicalizer.surtCanonicalize(query.get("url"));

        offset = Long.parseLong(query.getOrDefault("offset", "0"));
        limit = Long.parseLong(query.getOrDefault("limit", "10000"));
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
        int results = 0;
        int i = 0;
        for (Capture capture : index.query(queryUrl, accessPoint)) {
            if (i < offset) {
                i++;
                continue;
            } else if (i >= offset + limit) {
                break;
            }
            i++;

            if (!wroteHeader) {
                writeHeader(out, "resultstypecapture");
                out.writeStartElement("results");
                wroteHeader = true;
            }
            out.writeStartElement("result");
            writeElement(out, "compressedoffset", capture.compressedoffset);
            writeElement(out, "mimetype", capture.mimetype);
            writeElement(out, "file", capture.file);
            writeElement(out, "redirecturl", capture.redirecturl);
            writeElement(out, "urlkey", capture.urlkey);
            writeElement(out, "digest", capture.digest);
            writeElement(out, "httpresponsecode", capture.status);
            writeElement(out, "robotflags", "-"); // TODO
            writeElement(out, "url", capture.original);
            writeElement(out, "capturedate", capture.timestamp);
            out.writeEndElement(); // </result>
            results++;
        }

        if (wroteHeader) {
            out.writeEndElement(); // </results>
        } else {
            writeNotFoundError(out);
        }

        log.fine("[" + results + " results] " + queryUrl);
    }

    private void prefixQuery(XMLStreamWriter out) throws XMLStreamException {
        boolean wroteHeader = false;
        int i = 0;
        Resources it = new Resources(index.prefixQuery(queryUrl, accessPoint).iterator());
        while (it.hasNext()) {
            Resource resource = it.next();
            if (i < offset) {
                i++;
                continue;
            } else if (i >= offset + limit) {
                break;
            }
            i++;

            if (!wroteHeader) {
                writeHeader(out, "resultstypeurl");
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
        }

        if (wroteHeader) {
            out.writeEndElement(); // </results>
        } else {
            writeNotFoundError(out);
        }
    }

    private void writeHeader(XMLStreamWriter out, String resultsType) throws XMLStreamException {
        out.writeStartElement("request");
        writeElement(out, "startdate", "19960101000000");
        writeElement(out, "enddate", Capture.arcTimeFormat.format(LocalDateTime.now(ZoneOffset.UTC)));
        writeElement(out, "type", queryType);
        writeElement(out, "firstreturned", offset);
        writeElement(out, "url", queryUrl);
        writeElement(out, "resultsrequested", limit);
        writeElement(out, "resultstype", resultsType);
        out.writeEndElement(); // </request>
    }

    private static void writeNotFoundError(XMLStreamWriter out) throws XMLStreamException {
        out.writeStartElement("error");
        writeElement(out, "title", "Resource Not In Archive");
        writeElement(out, "message", "The Resource you requested is not in this archive.");
        out.writeEndElement();
    }

    public static NanoHTTPD.Response query(NanoHTTPD.IHTTPSession session, Index index) {
        return new XmlQuery(session, index).streamResults();
    }

    /**
     * Groups together all captures of the same URL.
     */
    private static class Resources implements Iterator<Resource> {
        private final Iterator<Capture> captures;
        private Capture capture = null;

        Resources(Iterator<Capture> captures) {
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
    }

    static class Resource {
        long captures;
        long versions;
        Capture firstCapture;
        Capture lastCapture;
    }
}
