package outbackcdx;

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
