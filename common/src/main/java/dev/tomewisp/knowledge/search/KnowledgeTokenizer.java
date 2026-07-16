package dev.tomewisp.knowledge.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class KnowledgeTokenizer {
    public List<String> tokenize(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String normalized = normalize(value);
        List<String> tokens = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        normalized.codePoints().forEach(codePoint -> {
            if (Character.isLetterOrDigit(codePoint)) {
                run.appendCodePoint(codePoint);
                if (isCjk(codePoint)) {
                    tokens.add(new String(Character.toChars(codePoint)));
                }
            } else {
                flush(run, tokens);
            }
        });
        flush(run, tokens);
        return List.copyOf(tokens);
    }

    public String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip();
    }

    private static void flush(StringBuilder run, List<String> tokens) {
        if (!run.isEmpty()) {
            tokens.add(run.toString());
            run.setLength(0);
        }
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
