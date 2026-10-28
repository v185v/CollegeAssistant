package xyz.v185v.collegeassistant.ingestion.crawl;

import java.net.URI;
import java.util.regex.Pattern;

/** 有界的仅路径 glob：* 只匹配当前段，** 可跨段，? 匹配一个字符。 */
public final class PathGlob {

    private final Pattern pattern;

    public PathGlob(String expression) {
        if (expression == null || expression.isBlank() || !expression.startsWith("/")
                || expression.indexOf('#') >= 0) {
            throw new IllegalArgumentException("Path glob must be a nonblank absolute path pattern");
        }
        this.pattern = Pattern.compile(toRegex(expression.strip()));
    }

    public boolean matches(URI uri) {
        return uri != null && matches(UrlCanonicalizer.normalizedPath(uri));
    }

    public boolean matches(String normalizedPath) {
        return normalizedPath != null && pattern.matcher(normalizedPath).matches();
    }

    private String toRegex(String expression) {
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < expression.length(); index++) {
            char character = expression.charAt(index);
            if (character == '*') {
                if (index + 1 < expression.length() && expression.charAt(index + 1) == '*') {
                    regex.append(".*");
                    index++;
                } else {
                    regex.append("[^/]*");
                }
            } else if (character == '?') {
                regex.append("[^/]");
            } else {
                if ("\\.[]{}()+-^$|".indexOf(character) >= 0) {
                    regex.append('\\');
                }
                regex.append(character);
            }
        }
        return regex.append('$').toString();
    }
}
