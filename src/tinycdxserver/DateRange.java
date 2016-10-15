package tinycdxserver;

import java.util.Date;

public class DateRange {
    public Date from;
    public Date until;

    boolean contains(Date date) {
        return (from == null || from.compareTo(date) < 0) &&
                (until == null || until.compareTo(date) > 0);
    }
}
