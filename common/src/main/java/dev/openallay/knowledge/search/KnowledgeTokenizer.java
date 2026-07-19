package dev.openallay.knowledge.search;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class KnowledgeTokenizer {
    public List<String> tokenize(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String normalized = normalize(value);
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        StringBuilder run = new StringBuilder();
        Character.UnicodeScript[] script = new Character.UnicodeScript[1];
        normalized.codePoints().forEach(codePoint -> {
            if (Character.isLetterOrDigit(codePoint)) {
                Character.UnicodeScript next = Character.UnicodeScript.of(codePoint);
                if (!run.isEmpty() && splitScript(script[0], next)) {
                    flush(run, tokens, script[0]);
                }
                run.appendCodePoint(codePoint);
                script[0] = next;
            } else {
                flush(run, tokens, script[0]);
                script[0] = null;
            }
        });
        flush(run, tokens, script[0]);
        if (normalized.codePoints().noneMatch(Character::isWhitespace)
                && normalized.codePoints().anyMatch(codePoint -> !Character.isLetterOrDigit(codePoint))) {
            tokens.add(normalized);
        }
        return List.copyOf(tokens);
    }

    public String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip();
    }

    private static void flush(
            StringBuilder run,
            LinkedHashSet<String> tokens,
            Character.UnicodeScript script) {
        if (!run.isEmpty()) {
            String value = run.toString();
            tokens.add(value);
            if (isCjk(script)) {
                int[] codePoints = value.codePoints().toArray();
                for (int codePoint : codePoints) {
                    tokens.add(new String(Character.toChars(codePoint)));
                }
                for (int index = 0; index + 1 < codePoints.length; index++) {
                    tokens.add(new String(codePoints, index, 2));
                }
            }
            run.setLength(0);
        }
    }

    private static boolean splitScript(Character.UnicodeScript current, Character.UnicodeScript next) {
        return current != null && isCjk(current) != isCjk(next);
    }

    private static boolean isCjk(Character.UnicodeScript script) {
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
