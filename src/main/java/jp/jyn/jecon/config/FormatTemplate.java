package jp.jyn.jecon.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FormatTemplate {
    private final List<String> segments;
    private final List<String> placeholders;

    public FormatTemplate(String template) {
        segments = new ArrayList<>();
        placeholders = new ArrayList<>();

        int i = 0;
        int len = template.length();
        StringBuilder literal = new StringBuilder();
        while (i < len) {
            char c = template.charAt(i);
            if (c == '{') {
                int end = template.indexOf('}', i + 1);
                if (end < 0) {
                    literal.append(template, i, len);
                    i = len;
                } else {
                    segments.add(literal.toString());
                    literal.setLength(0);
                    placeholders.add(template.substring(i + 1, end));
                    i = end + 1;
                }
            } else {
                literal.append(c);
                i++;
            }
        }
        segments.add(literal.toString());
    }

    public String format(Map<String, String> variables) {
        StringBuilder sb = new StringBuilder();
        sb.append(segments.get(0));
        for (int i = 0; i < placeholders.size(); i++) {
            String value = variables.get(placeholders.get(i));
            sb.append(value != null ? value : "{" + placeholders.get(i) + "}");
            sb.append(segments.get(i + 1));
        }
        return sb.toString();
    }
}
