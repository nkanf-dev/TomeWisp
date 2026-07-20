package dev.openallay.knowledge.online;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small deterministic HTML-to-semantic-section projection for fixed public documentation. */
final class OnlineKnowledgeHtml {
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern IGNORED = Pattern.compile(
            "(?is)<(head|script|style|noscript|svg|nav|footer)[^>]*>.*?</\\1>");
    private static final Pattern BLOCK_END = Pattern.compile(
            "(?is)</(?:p|div|li|tr|h[1-6]|section|article)>|<br\\s*/?>");
    private static final Pattern TAG = Pattern.compile("(?is)<[^>]+>");

    private OnlineKnowledgeHtml() {}

    static Optional<String> title(String html) {
        Matcher matcher = TITLE.matcher(html == null ? "" : html);
        return matcher.find()
                ? Optional.of(clean(matcher.group(1))).filter(value -> !value.isBlank())
                : Optional.empty();
    }

    static List<OnlineKnowledgeSource.RawSection> sections(String title, String html) {
        String withoutIgnored = IGNORED.matcher(html == null ? "" : html).replaceAll(" ");
        String lines = BLOCK_END.matcher(withoutIgnored).replaceAll("\n");
        String text = clean(TAG.matcher(lines).replaceAll(" "));
        ArrayList<OnlineKnowledgeSource.RawSection> sections = new ArrayList<>();
        int index = 1;
        for (String paragraph : text.split("(?:\\R\\s*){1,}")) {
            String normalized = paragraph.replaceAll("[ \\t]+", " ").strip();
            if (normalized.isBlank()) continue;
            sections.add(new OnlineKnowledgeSource.RawSection(
                    "section-" + index,
                    index == 1 ? title : title + " · " + index,
                    normalized));
            index++;
        }
        if (sections.isEmpty()) {
            throw new OnlineKnowledgeException(
                    "online_parse_failed", "Public knowledge document has no readable text");
        }
        return List.copyOf(sections);
    }

    private static String clean(String value) {
        return value
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" ?\\R ?", "\n")
                .strip();
    }
}
