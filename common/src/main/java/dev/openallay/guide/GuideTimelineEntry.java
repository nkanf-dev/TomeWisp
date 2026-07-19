package dev.openallay.guide;

import dev.openallay.guide.semantic.SemanticDocument;
import dev.openallay.guide.semantic.SemanticMessageParser;
import java.util.List;

public sealed interface GuideTimelineEntry
        permits GuideTimelineEntry.Assistant, GuideTimelineEntry.Tool {
    int ordinal();

    record Assistant(
            int ordinal,
            String text,
            SemanticDocument semantic,
            boolean streaming,
            List<GuideSource> sources) implements GuideTimelineEntry {
        private static final SemanticMessageParser DEFAULT_PARSER = new SemanticMessageParser();

        public Assistant {
            requireOrdinal(ordinal);
            text = text == null ? "" : text;
            java.util.Objects.requireNonNull(semantic, "semantic");
            sources = List.copyOf(sources);
        }

        public Assistant(
                int ordinal,
                String text,
                boolean streaming,
                List<GuideSource> sources) {
            this(
                    ordinal,
                    text,
                    DEFAULT_PARSER.parse(text),
                    streaming,
                    sources);
        }
    }

    record Tool(int ordinal, GuideToolActivity activity) implements GuideTimelineEntry {
        public Tool {
            requireOrdinal(ordinal);
            java.util.Objects.requireNonNull(activity, "activity");
        }
    }

    private static void requireOrdinal(int ordinal) {
        if (ordinal < 0) {
            throw new IllegalArgumentException("timeline ordinal must not be negative");
        }
    }
}
