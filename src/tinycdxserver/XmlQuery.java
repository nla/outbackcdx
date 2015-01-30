package tinycdxserver;

import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * Implements the Wayback RemoteCollection XmlQuery interface.
 */
public class XmlQuery {
    final static String DEFAULT_ENCODING = "UTF-8";

    public enum QueryType {
        URLQUERY {
            @Override
            boolean inScope(Record record, String queryUrl) {
                return record.urlkey.equals(queryUrl);
            }

            @Override
            void perform(XMLStreamWriter out, String queryUrl, DBIterator it, Record record) throws XMLStreamException {
                do {
                    out.writeStartElement("result");
                    writeElement(out, "compressedoffset", record.compressedoffset);
                    writeElement(out, "mimetype", record.mimetype);
                    writeElement(out, "file", record.file);
                    writeElement(out, "redirecturl", record.redirecturl);
                    writeElement(out, "urlkey", record.urlkey);
                    writeElement(out, "digest", record.digest);
                    writeElement(out, "httpresponsecode", record.status);
                    writeElement(out, "robotflags", "-"); // TODO
                    writeElement(out, "url", record.original);
                    writeElement(out, "capturedate", record.timestamp);
                    out.writeEndElement(); // </result>
                    if (!it.hasNext()) break;
                    record = new Record(it.next());;
                } while (inScope(record, queryUrl));
            }

            @Override
            String resultsType() {
                return "resultstypecapture";
            }
        },
        PREFIXQUERY {
            @Override
            boolean inScope(Record record, String queryUrl) {
                return record.urlkey.startsWith(queryUrl);
            }

            @Override
            void perform(XMLStreamWriter out, String queryUrl, DBIterator it, Record record) throws XMLStreamException {
                do {
                    long captures = 0, versions = 0;
                    Record firstCapture = record;
                    Record lastCapture = record;
                    String lastDigest = null;
                    while (record.urlkey.equals(firstCapture.urlkey)) {
                        if (!record.digest.equals(lastDigest)) {
                            versions++;
                            lastDigest = record.digest;
                        }
                        captures++;
                        lastCapture = record;
                        if (!it.hasNext()) {
                            record = null;
                            break;
                        }
                        record = new Record(it.next());
                    }
                    out.writeStartElement("result");
                    writeElement(out, "urlkey", lastCapture.urlkey);
                    writeElement(out, "originalurl", lastCapture.original);
                    writeElement(out, "numversions", versions);
                    writeElement(out, "numcaptures", captures);
                    writeElement(out, "firstcapturets", firstCapture.timestamp);
                    writeElement(out, "lastcapturets", lastCapture.timestamp);
                    out.writeEndElement(); // </result>
                } while (record != null && inScope(record, queryUrl));
            }

            @Override
            String resultsType() {
                return "resultstypeurl";
            }
        };

        abstract boolean inScope(Record record, String queryUrl);
        abstract void perform(XMLStreamWriter out, String queryUrl, DBIterator it, Record record) throws XMLStreamException;
        abstract String resultsType();

        static QueryType byName(String typeName) {
            if (typeName == null) {
                return QueryType.URLQUERY;
            } else {
                return QueryType.valueOf(typeName.toUpperCase());
            }
        }
    }

    private static Map<String,String> decodeQueryString(String q) {

        Map<String,String> m = new HashMap<String, String>();
        for (String entry : q.split(" ")) {
            String[] fields = entry.split(":", 2);
            String key = UrlCanonicalizer.urlDecode(fields[0]);
            String value = UrlCanonicalizer.urlDecode(fields[1]);
            m.put(key, value);
        }
        return m;
    }

    private static void writeElement(XMLStreamWriter out, String name, Object value) throws XMLStreamException {
        out.writeStartElement(name);
        out.writeCharacters(value.toString());
        out.writeEndElement();
    }

    public static NanoHTTPD.Response query(NanoHTTPD.IHTTPSession session, final DB index) {
        Map<String,String> params = session.getParms();
        Map<String,String> query = decodeQueryString(params.get("q"));

        final QueryType queryType = QueryType.byName(query.get("type"));
        final String queryUrl = UrlCanonicalizer.surtCanonicalize(query.get("url"));

        final long offset = 0; // TODO
        final long limit = 10000; // TODO

        System.out.println("xmlquery " + query.get("url") + " canon " + queryUrl);
        return new NanoHTTPD.Response(NanoHTTPD.Response.Status.OK, "application/xml;charset=" + DEFAULT_ENCODING, new NanoHTTPD.IStreamer() {
            @Override
            public void stream(OutputStream outputStream) throws IOException {
                DBIterator it = index.iterator();
                try {
                    it.seek(Record.encodeKey(queryUrl, 0));
                    XMLOutputFactory factory = XMLOutputFactory.newInstance();
                    XMLStreamWriter out = factory.createXMLStreamWriter(outputStream, DEFAULT_ENCODING);
                    out.writeStartDocument(DEFAULT_ENCODING, "1.0");
                    out.writeStartElement("wayback");

                    Record record = null;
                    if (it.hasNext()) {
                        record = new Record(it.next());
                    }
                    if (record != null && queryType.inScope(record, queryUrl)) {
                        out.writeStartElement("request");
                        writeElement(out, "startdate", "19960101000000");
                        writeElement(out, "enddate", Record.arcTimeFormat.format(LocalDateTime.now(ZoneOffset.UTC)));
                        writeElement(out, "type", queryType.name().toLowerCase());
                        writeElement(out, "firstreturned", offset);
                        writeElement(out, "url", queryUrl);
                        writeElement(out, "resultsrequested", limit);
                        writeElement(out, "resultstype", queryType.resultsType());
                        out.writeEndElement(); // </request>

                        out.writeStartElement("results");
                        queryType.perform(out, queryUrl, it, record);
                        out.writeEndElement(); // </results>
                    } else {
                        out.writeStartElement("error");
                        writeElement(out, "title", "Resource Not In Archive");
                        writeElement(out, "message", "The Resource you requested is not in this archive.");
                        out.writeEndElement();
                    }
                    out.writeEndElement(); // </wayback>
                    out.writeEndDocument();
                    out.flush();
                } catch (XMLStreamException e) {
                    throw new RuntimeException(e);
                } finally {
                    it.close();
                }
            }
        });
    }
}
