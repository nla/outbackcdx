package tinycdxserver;

import java.time.Period;
import java.util.ArrayList;
import java.util.List;

public class AccessRule {
    Long id;
    Long policyId;
    List<String> surts = new ArrayList<>();
    DateRange captured;
    DateRange retrieved;
    Period embargo;
    String privateComment;
    String publicComment;
    boolean enabled;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AccessRule that = (AccessRule) o;

        if (enabled != that.enabled) return false;
        if (id != null ? !id.equals(that.id) : that.id != null) return false;
        if (policyId != null ? !policyId.equals(that.policyId) : that.policyId != null)
            return false;
        if (surts != null ? !surts.equals(that.surts) : that.surts != null)
            return false;
        if (captured != null ? !captured.equals(that.captured) : that.captured != null)
            return false;
        if (retrieved != null ? !retrieved.equals(that.retrieved) : that.retrieved != null)
            return false;
        if (embargo != null ? !embargo.equals(that.embargo) : that.embargo != null)
            return false;
        if (privateComment != null ? !privateComment.equals(that.privateComment) : that.privateComment != null)
            return false;
        return publicComment != null ? publicComment.equals(that.publicComment) : that.publicComment == null;

    }

    @Override
    public int hashCode() {
        int result = id != null ? id.hashCode() : 0;
        result = 31 * result + (policyId != null ? policyId.hashCode() : 0);
        result = 31 * result + (surts != null ? surts.hashCode() : 0);
        result = 31 * result + (captured != null ? captured.hashCode() : 0);
        result = 31 * result + (retrieved != null ? retrieved.hashCode() : 0);
        result = 31 * result + (embargo != null ? embargo.hashCode() : 0);
        result = 31 * result + (privateComment != null ? privateComment.hashCode() : 0);
        result = 31 * result + (publicComment != null ? publicComment.hashCode() : 0);
        result = 31 * result + (enabled ? 1 : 0);
        return result;
    }
}
