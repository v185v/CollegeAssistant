package xyz.v185v.collegeassistant.ingestion.crawl;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 最小 robots.txt 策略，按最长规则优先，并默认允许作为回退。 */
public final class RobotsPolicy {

    private static final RobotsPolicy ALLOW_ALL = new RobotsPolicy(List.of());

    private final List<Rule> rules;

    private RobotsPolicy(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static RobotsPolicy allowAll() {
        return ALLOW_ALL;
    }

    public static RobotsPolicy parse(String content, String userAgent) {
        if (content == null || content.isBlank()) {
            return allowAll();
        }
        String normalizedAgent = userAgent == null || userAgent.isBlank()
                ? "collegeassistantbot" : userAgent.strip().toLowerCase(Locale.ROOT);
        List<Rule> selected = new ArrayList<>();
        List<String> agents = new ArrayList<>();
        boolean rulesStarted = false;
        for (String sourceLine : content.lines().toList()) {
            String line = stripComment(sourceLine).strip();
            if (line.isEmpty()) {
                agents.clear();
                rulesStarted = false;
                continue;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String name = line.substring(0, separator).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).strip();
            if (name.equals("user-agent")) {
                if (rulesStarted) {
                    agents.clear();
                    rulesStarted = false;
                }
                agents.add(value.toLowerCase(Locale.ROOT));
                continue;
            }
            if (!name.equals("allow") && !name.equals("disallow")) {
                continue;
            }
            rulesStarted = true;
            if (value.isEmpty() || agents.stream().noneMatch(agent ->
                    agent.equals("*") || normalizedAgent.contains(agent))) {
                continue;
            }
            selected.add(new Rule(value, name.equals("allow")));
        }
        return selected.isEmpty() ? allowAll() : new RobotsPolicy(selected);
    }

    public boolean allows(URI uri) {
        return uri != null && allows(UrlCanonicalizer.normalizedPath(uri));
    }

    public boolean allows(String normalizedPath) {
        if (normalizedPath == null) {
            return false;
        }
        Rule winner = null;
        for (Rule rule : rules) {
            if (rule.matches(normalizedPath)
                    && (winner == null || rule.specificity() > winner.specificity()
                    || rule.specificity() == winner.specificity() && rule.allow())) {
                winner = rule;
            }
        }
        return winner == null || winner.allow();
    }

    private static String stripComment(String line) {
        int comment = line.indexOf('#');
        return comment < 0 ? line : line.substring(0, comment);
    }

    private record Rule(String path, boolean allow) {
        boolean matches(String candidate) {
            return candidate.startsWith(path);
        }

        int specificity() {
            return path.length();
        }
    }
}
