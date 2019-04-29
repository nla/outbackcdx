package outbackcdx.services;

import java.util.function.Predicate;

import outbackcdx.Capture;
import outbackcdx.FilterPlugin;
import outbackcdx.Query;

public class service implements FilterPlugin {
    public service() {
        System.err.println("FilterPlugin 'service' loaded");
    }

    public Predicate<Capture> newFilter(Query query) {
        System.err.println("FilterPlugin 'service' received query");
        return capture -> !capture.original.startsWith("metadata://");
    }
}
