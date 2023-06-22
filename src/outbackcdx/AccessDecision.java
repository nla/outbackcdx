package outbackcdx;

public class AccessDecision {
    private final boolean allowed;
    private final AccessRule rule;
    private final AccessPolicy policy;

    public AccessDecision(boolean allowed, AccessRule rule, AccessPolicy policy) {
        this.allowed = allowed;
        this.rule = rule;
        this.policy = policy;
    }

    public boolean isAllowed() {
        return allowed;
    }

    public String getPublicMessage() {
        if (rule == null) return null;
        return rule.publicMessage;
    }
}
