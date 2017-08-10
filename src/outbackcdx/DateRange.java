package outbackcdx;

import java.text.SimpleDateFormat;
import java.util.Date;

public class DateRange {
    public Date start;
    public Date end;

    boolean contains(Date date) {
        return (start == null || start.compareTo(date) < 0) &&
                (end == null || end.compareTo(date) > 0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DateRange that = (DateRange) o;

        if (start != null ? !start.equals(that.start) : that.start != null) return false;
        return end != null ? end.equals(that.end) : that.end == null;
    }

    @Override
    public int hashCode() {
        int result = start != null ? start.hashCode() : 0;
        result = 31 * result + (end != null ? end.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
        return "DateRange:[" + (start == null ? "NULL" : sdf.format(start)) + " TO "
              + (start == null ? "NULL" : sdf.format(end)) + "]";
    }
}
