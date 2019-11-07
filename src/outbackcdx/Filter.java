package outbackcdx;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implements cdx server api field filters. Supports regex and substring filters
 * like <a href=
 * "https://github.com/iipc/openwayback/blob/0991554/wayback-cdx-server-core/src/main/java/org/archive/cdxserver/filter/FieldRegexFilter.java">FieldRegexFilter</a>.
 * But does not support whole line matching. A field is required.
 */
interface Filter extends Predicate<Capture> {
    static final Pattern FILTER_RE = Pattern.compile("(~)?(!)?(\\w+):(.*)");

    public static Filter fromSpec(String spec) {
        Matcher m = FILTER_RE.matcher(spec);
        if (!m.matches()) throw new IllegalArgumentException("Invalid filter: " + spec);

        String field = m.group(3);
        boolean invert = m.group(2) != null;
        boolean substring = m.group(1) != null;

        if (substring) {
            return new SubstringFilter(m.group(4), field, invert);
        } else {
            return new RegexFilter(m.group(4), field, invert);
        }
    }

    public static Filter collapseToFirst(String spec) {
        String[] splits = spec.split(":", 2);
        String field = splits[0];
        Integer substringLength = null;
        if (splits.length > 1) {
            substringLength = Integer.parseInt(splits[1]);
        }
        return new CollapseToFirst(field, substringLength);
    }

    public class CollapseToFirst implements Filter {
        private String field;
        private Integer substringLength;
        private String lastValue;

        public CollapseToFirst(String field, Integer substringLength) {
            this.field = field;
            this.substringLength = substringLength;
        }

        @Override
        public boolean test(Capture t) {
            String value = t.get(field).toString();
            if (substringLength != null) {
                value = value.substring(0, substringLength);
            }
            boolean result = !value.equals(lastValue);
            lastValue = value;
            return result;
        }
    }

    public static Iterable<Capture> collapseToLast(Iterable<Capture> captures, String spec) {
        return new Iterable<Capture>() {
            @Override
            public Iterator<Capture> iterator() {
                return new CollapseToLast(captures.iterator(), spec);
            }
        };
    }

    /**
     * Not a {@link Filter} because it needs to look ahead to the next line to know
     * whether to include or exclude the current line.
     */
    public static class CollapseToLast implements Iterator<Capture> {

        protected Iterator<Capture> inner;
        protected String field;
        protected Integer substringLength;
        protected Capture next;
        protected Capture innerNext;

        public CollapseToLast(Iterator<Capture> inner, String spec) {
            this.inner = inner;

            String[] splits = spec.split(":", 2);
            field = splits[0];
            substringLength = null;
            if (splits.length > 1) {
                substringLength = Integer.parseInt(splits[1]);
            }
        }

        protected boolean shouldCollapse(Capture cap1, Capture cap2) {
            String value1 = cap1.get(field).toString();
            String value2 = cap2.get(field).toString();
            if (substringLength != null) {
                value1 = value1.substring(0, substringLength);
                value2 = value2.substring(0, substringLength);
            }
            return value1.equals(value2);
        }

        @Override
        public boolean hasNext() {
            while (next == null) {
                Capture innerPrev = innerNext;
                if (inner.hasNext()) {
                    innerNext = inner.next();
                } else {
                    innerNext = null;
                }

                if (innerPrev != null && (innerNext == null || !shouldCollapse(innerPrev, innerNext))) {
                    next = innerPrev;
                }
                if (innerNext == null) {
                    break;
                }
            }
            return next != null;
        }

        @Override
        public Capture next() {
            if (hasNext()) {
                Capture tmp = next;
                next = null;
                return tmp;
            } else {
                throw new NoSuchElementException();
            }
        }

    }

    abstract class BaseFilter implements Filter {
        protected String field;
        protected boolean inverted;

        public BaseFilter(String field, boolean inverted) {
            this.field = field;
            this.inverted = inverted;

            // validate field name so we fail early
            if (field == null) {
                throw new IllegalArgumentException("field is required");
            }
            new Capture().get(field);
        }
    }

    public class SubstringFilter extends BaseFilter {
        protected String substring;

        public SubstringFilter(String substring, String field, boolean invert) {
            super(field, invert);
            this.substring = substring;
        }

        @Override
        public boolean test(Capture capture) {
            Object value = capture.get(field);
            String str = value == null ? "" : value.toString();
            return str.contains(substring) != inverted;
        }

    }

    public class RegexFilter extends BaseFilter {
        protected Pattern regex;

        public RegexFilter(String regex, String field, boolean invert) {
            super(field, invert);
            this.regex = Pattern.compile(regex);
        }

        @Override
        public boolean test(Capture capture) {
            Object value = capture.get(field);
            String str = value == null ? "" : value.toString();
            return regex.matcher(str).matches() != inverted;
        }
    }
}
