package tinycdxserver;

import java.time.Period;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class AccessRule {
    Long id;
    AccessPolicy policy;
    List<String> surts = new ArrayList<>();
    DateRange captured;
    DateRange accessed;
    Period periodSinceCapture;
    String privateComment;
    String publicComment;
    boolean enabled;

    /**
     * True if this rule is applicable to the given capture and access times.
     */
    public boolean matchesDates(Date captureTime, Date accessTime) {
        return (captured == null || captured.contains(captureTime)) &&
                (accessed == null || accessed.contains(accessTime)) &&
                (periodSinceCapture == null || captureTime.toInstant().plus(periodSinceCapture).isBefore(accessTime.toInstant()));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AccessRule that = (AccessRule) o;

        if (enabled != that.enabled) return false;
        if (id != null ? !id.equals(that.id) : that.id != null) return false;
        if (policy != null ? !policy.equals(that.policy) : that.policy != null)
            return false;
        if (surts != null ? !surts.equals(that.surts) : that.surts != null)
            return false;
        if (captured != null ? !captured.equals(that.captured) : that.captured != null)
            return false;
        if (accessed != null ? !accessed.equals(that.accessed) : that.accessed != null)
            return false;
        if (periodSinceCapture != null ? !periodSinceCapture.equals(that.periodSinceCapture) : that.periodSinceCapture != null)
            return false;
        if (privateComment != null ? !privateComment.equals(that.privateComment) : that.privateComment != null)
            return false;
        return publicComment != null ? publicComment.equals(that.publicComment) : that.publicComment == null;

    }

    @Override
    public int hashCode() {
        int result = id != null ? id.hashCode() : 0;
        result = 31 * result + (policy != null ? policy.hashCode() : 0);
        result = 31 * result + (surts != null ? surts.hashCode() : 0);
        result = 31 * result + (captured != null ? captured.hashCode() : 0);
        result = 31 * result + (accessed != null ? accessed.hashCode() : 0);
        result = 31 * result + (periodSinceCapture != null ? periodSinceCapture.hashCode() : 0);
        result = 31 * result + (privateComment != null ? privateComment.hashCode() : 0);
        result = 31 * result + (publicComment != null ? publicComment.hashCode() : 0);
        result = 31 * result + (enabled ? 1 : 0);
        return result;
    }
}
