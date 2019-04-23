package service;

import java.util.function.Predicate;

import outbackcdx.Capture;
import outbackcdx.FilterPlugin;
import outbackcdx.Query;

class service implements FilterPlugin {
    public Predicate<Capture> newFilter(Query query) {
        System.out.println("FilterPlugin received query");
        return capture -> true;
    }
}
