package outbackcdx;

public class AccessRuleError {
    private final Long ruleId;
    private final int patternIndex;
    private final String message;

    public AccessRuleError(Long ruleId, int patternIndex, String message) {
        this.ruleId = ruleId;
        this.patternIndex = patternIndex;
        this.message = message;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public int getPatternIndex() {
        return patternIndex;
    }

    public String getMessage() {
        return message;
    }
}
