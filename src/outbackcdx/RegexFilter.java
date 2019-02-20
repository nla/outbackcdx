package outbackcdx;

import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RegexFilter implements Predicate<Capture> {
    private static final Pattern FILTER_RE = Pattern.compile("(!)?(\\w+):(.*)");
    private boolean inverted;
    private String field;
    private Pattern regex;

    RegexFilter(String pattern) {
        Matcher m = FILTER_RE.matcher(pattern);
        if (!m.matches()) throw new IllegalArgumentException("Invalid filter: " + pattern);
        inverted = m.group(1) != null;
        field = m.group(2);
        regex = Pattern.compile(m.group(3));

        // validate field name so we fail early
        new Capture().get(field);
    }

    @Override
    public boolean test(Capture capture) {
        Object value = capture.get(field);
        String str = value == null ? "" : value.toString();
        return regex.matcher(str).matches() != inverted;
    }
}
