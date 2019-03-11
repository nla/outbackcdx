package outbackcdx;

import java.util.function.Predicate;

public interface FilterPlugin {
    public Predicate<Capture> newFilter(Query query);
}
