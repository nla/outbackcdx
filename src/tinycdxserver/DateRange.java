package tinycdxserver;

import java.util.Date;

public class DateRange {
    public Date start;
    public Date end;

    boolean contains(Date date) {
        return (start == null || start.compareTo(date) < 0) &&
                (end == null || end.compareTo(date) > 0);
    }
}
