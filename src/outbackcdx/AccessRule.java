package outbackcdx;

import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

public class AccessRule {
    Long id;
    Long policyId;
    List<String> urlPatterns = new ArrayList<>();
    DateRange captured;
    DateRange accessed;
    Period period;
    String privateComment;
    String publicMessage;
    boolean enabled;
    Date created;
    Date modified;

    /**
     * True if this rule is applicable to the given capture and access times.
     */
    public boolean matchesDates(Date captureTime, Date accessTime) {
        return (captured == null || captured.contains(captureTime)) &&
                (accessed == null || accessed.contains(accessTime)) &&
                (period == null || period.equals(period.ZERO) || isWithinPeriod(captureTime, accessTime));
    }

    private boolean isWithinPeriod(Date captureTime, Date accessTime) {
        // do the period calculation in the local timezone so that 'years' periods work
        LocalDateTime localCaptureTime = LocalDateTime.ofInstant(captureTime.toInstant(), ZoneId.systemDefault());
        LocalDateTime localAccessTime = LocalDateTime.ofInstant(accessTime.toInstant(), ZoneId.systemDefault());
            return localAccessTime.isBefore(localCaptureTime.plus(period));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AccessRule that = (AccessRule) o;

        if (enabled != that.enabled) return false;
        if (id != null ? !id.equals(that.id) : that.id != null) return false;
        if (policyId != null ? !policyId.equals(that.policyId) : that.policyId != null)
            return false;
        if (urlPatterns != null ? !urlPatterns.equals(that.urlPatterns) : that.urlPatterns != null)
            return false;
        if (captured != null ? !captured.equals(that.captured) : that.captured != null)
            return false;
        if (accessed != null ? !accessed.equals(that.accessed) : that.accessed != null)
            return false;
        if (period != null ? !period.equals(that.period) : that.period != null)
            return false;
        if (privateComment != null ? !privateComment.equals(that.privateComment) : that.privateComment != null)
            return false;
        return publicMessage != null ? publicMessage.equals(that.publicMessage) : that.publicMessage == null;

    }

    @Override
    public int hashCode() {
        int result = id != null ? id.hashCode() : 0;
        result = 31 * result + (policyId != null ? policyId.hashCode() : 0);
        result = 31 * result + (urlPatterns != null ? urlPatterns.hashCode() : 0);
        result = 31 * result + (captured != null ? captured.hashCode() : 0);
        result = 31 * result + (accessed != null ? accessed.hashCode() : 0);
        result = 31 * result + (period != null ? period.hashCode() : 0);
        result = 31 * result + (privateComment != null ? privateComment.hashCode() : 0);
        result = 31 * result + (publicMessage != null ? publicMessage.hashCode() : 0);
        result = 31 * result + (enabled ? 1 : 0);
        return result;
    }

    Stream<String> ssurtPrefixes() {
        return urlPatterns.stream().map(AccessControl::toSsurtPrefix);
    }

    List<AccessRuleError> validate() {
        List<AccessRuleError> errors = new ArrayList<>();
        for (int i = 0; i < urlPatterns.size(); i++) {
            String pattern = urlPatterns.get(i);
            if (pattern.startsWith("*.") && pattern.contains("/")) {
                errors.add(new AccessRuleError(id, i, "can't use a domain wildcard with path"));
            } else if (pattern.isEmpty()) {
                errors.add(new AccessRuleError(id, i, "URL pattern can't be blank"));
            }
        }

        if (urlPatterns.isEmpty()) {
            errors.add(new AccessRuleError(id, -1, "rule must have at least one URL pattern"));
        }
        return errors;
    }

    @Override
    public String toString() {
        return "AccessRule{" +
                "id=" + id +
                ", policyId=" + policyId +
                ", urlPatterns=" + urlPatterns +
                ", captured=" + captured +
                ", accessed=" + accessed +
                ", period=" + period +
                ", privateComment='" + privateComment + '\'' +
                ", publicMessage='" + publicMessage + '\'' +
                ", enabled=" + enabled +
                ", created=" + created +
                ", modified=" + modified +
                '}';
    }
}
