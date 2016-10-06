package tinycdxserver;

import com.googlecode.concurrenttrees.radix.node.concrete.DefaultCharArrayNodeFactory;
import com.googlecode.concurrenttrees.radixinverted.ConcurrentInvertedRadixTree;
import com.googlecode.concurrenttrees.radixinverted.InvertedRadixTree;

import java.time.Period;
import java.util.Date;

public class AccessControl {
    public static class DateRange {
        public Date from;
        public Date until;

        boolean contains(Date date) {
            return (from == null || from.compareTo(date) < 0) &&
                    (until == null || until.compareTo(date) > 0);
        }
    }

    public static class Rule {
        Long id;
        String surt;
        boolean exactMatch;
        boolean deliverable;
        boolean discoverable;
        DateRange captured;
        DateRange retrieved;
        Period embargo;
        String who;
        String privateComment;
        String message;
        boolean enabled;

        boolean matches(Request request) {
            return enabled &&
                    (exactMatch ? request.surt.equals(surt) : request.surt.startsWith(surt)) &&
                    (who == null || who.equals(request.who)) &&
                    (captured == null || captured.contains(request.captureDate)) &&
                    (retrieved == null || retrieved.contains(request.retrievalDate)) &&
                    (embargo == null || request.retrievalDate.toInstant().isAfter(request.captureDate.toInstant().plus(embargo)));
        }
    }

    public static class Request {
        String surt;
        String who;
        Date captureDate;
        Date retrievalDate;
    }

    public static class RuleSet {
        InvertedRadixTree<Rule> rules = new ConcurrentInvertedRadixTree<Rule>(new DefaultCharArrayNodeFactory());

        public Rule get(Request request) {
            Rule result = null;
            for (Rule rule: rules.getValuesForKeysPrefixing(request.surt)) {
                if (rule.matches(request)) {
                    result = rule;
                }
            }
            return result;
        }
    }
}

