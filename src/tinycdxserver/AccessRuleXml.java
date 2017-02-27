package tinycdxserver;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.stream.StreamSource;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Period;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Imports access rules from the Wayback Access Control Tool.
 */
public class AccessRuleXml {

    private static final Pattern SURT_REGEX = Pattern.compile(
            "(?<scheme>[a-zA-Z][a-zA-Z0-9]*:/*)?[(]" +
                    "(?<host>[^)/:]*)" +
                    "(?<port>: [0-9]+ )?" +
                    "(?: [)] (?<path> /.* ))?", Pattern.COMMENTS);

    static List<AccessRule> parseRules(InputStream stream) throws XMLStreamException {
        return parseRules(XMLInputFactory.newInstance().createXMLStreamReader(new StreamSource(stream)));
    }

    static List<AccessRule> parseRules(XMLStreamReader xml) throws XMLStreamException {
        List<AccessRule> rules = null;
        if (xml.nextTag() == XMLStreamConstants.START_ELEMENT) {
            switch (xml.getLocalName()) {
                case "list":
                    rules = parseRuleList(xml);
                    break;
                default:
                    throw new XMLStreamException("Unexpected tag: " + xml.getLocalName(), xml.getLocation());
            }
        } else {
            throw new XMLStreamException("Expected root element");
        }
        return rules;
    }

    private static List<AccessRule> parseRuleList(XMLStreamReader xml) throws XMLStreamException {
        List<AccessRule> rules = new ArrayList<>();
        while (xml.nextTag() == XMLStreamConstants.START_ELEMENT) {
            switch (xml.getLocalName()) {
                case "rule":
                    rules.add(parseRule(xml));
                    break;
                default:
                    throw new XMLStreamException("Unexpected tag: " + xml.getLocalName(), xml.getLocation());
            }
        }
        return rules;
    }

    private static String unreverseDomain(String host) {
        if (host.endsWith(",")) {
            host = host.substring(0, host.length() - 1);
        }
        StringBuilder out = new StringBuilder();
        int i = host.lastIndexOf(',', host.length());
        int j = host.length();
        while (i != -1) {
            out.append(host, i + 1, j);
            out.append('.');
            j = i;
            i = host.lastIndexOf(',', i - 1);
        }
        out.append(host, 0, j);
        return out.toString();
    }

    private static AccessRule parseRule(XMLStreamReader xml) throws XMLStreamException {
        String surt = "";
        boolean exactMatch = false;
        AccessRule rule = new AccessRule();
        rule.enabled = true;
        while (xml.nextTag() == XMLStreamConstants.START_ELEMENT) {
            switch (xml.getLocalName()) {
                case "id":
                    rule.id = Long.parseLong(xml.getElementText());
                    break;
                case "policy":
                    String policy = xml.getElementText();
                    switch (policy) {
                        case "":
                        case "allow":
                            rule.policyId = 0L;
                            break;
                        case "block":
                            rule.policyId = 2L;
                            break;
                        default:
                            throw new XMLStreamException("Unhandled policy: " + policy, xml.getLocation());
                    }
                    break;
                case "surt":
                    surt = xml.getElementText();
                    break;
                case "who":
                    xml.getElementText();
                    break;
                case "privateComment":
                    rule.privateComment = xml.getElementText();
                    if (rule.privateComment.equals("")) {
                        rule.privateComment = null;
                    }
                    break;
                case "publicComment":
                    rule.publicMessage = xml.getElementText();
                    if (rule.publicMessage.equals("")) {
                        rule.publicMessage = null;
                    }
                    break;
                case "exactMatch":
                    exactMatch = xml.getElementText().equals("true");
                    break;
                case "lastModified":
                    rule.modified = parseDate(xml);
                    break;
                case "captureStart":
                    if (rule.captured == null) {
                        rule.captured = new DateRange();
                    }
                    rule.captured.start = parseDate(xml);
                    break;
                case "captureEnd":
                    if (rule.captured == null) {
                        rule.captured = new DateRange();
                    }
                    rule.captured.end = parseDate(xml);
                    break;
                case "retrievalStart":
                    if (rule.accessed == null) {
                        rule.accessed = new DateRange();
                    }
                    rule.accessed.start = parseDate(xml);
                    break;
                case "retrievalEnd":
                    if (rule.accessed == null) {
                        rule.accessed = new DateRange();
                    }
                    rule.accessed.end = parseDate(xml);
                    break;
                case "secondsSinceCapture":
                    long seconds = Long.parseLong(xml.getElementText());
                    rule.period = Period.ofDays((int)(seconds / (60 * 60 * 24)));
                    break;
                default:
                    throw new XMLStreamException("Unexpected tag: " + xml.getLocalName(), xml.getLocation());
            }
        }

        Matcher m = SURT_REGEX.matcher(surt);
        if (m.matches()) {
            String scheme = m.group("scheme");
            String host = m.group("host");
            String path = m.group("path");
            String port = m.group("port");

            if (port == null) {
                port = "";
            }

            host = unreverseDomain(host);

            if (host.equals("")) {
                rule.urlPatterns.add("*");
            } else if (path == null) {
                rule.urlPatterns.add("*." + host);
            } else {
                if (exactMatch) {
                    rule.urlPatterns.add(scheme + host + port + path);
                } else {
                    rule.urlPatterns.add(scheme + host + port + path + "*");
                }
            }
        } else {
            throw new RuntimeException("invalid url: " + surt);
        }
        return rule;
    }

    private static Date parseDate(XMLStreamReader xml) throws XMLStreamException {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd kk:mm:ss.S");
        try {
            return format.parse(xml.getElementText());
        } catch (ParseException e) {
            throw new XMLStreamException("unable to parse date", xml.getLocation(),  e);
        }
    }
}
