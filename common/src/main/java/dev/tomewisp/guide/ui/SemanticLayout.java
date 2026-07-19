package dev.tomewisp.guide.ui;

import dev.tomewisp.guide.semantic.RichComponent;
import dev.tomewisp.guide.semantic.SemanticReference;
import java.util.List;

/** Detached native-render plan; no Minecraft registry or widget objects are retained. */
public record SemanticLayout(int width, int height, List<Line> lines, String narration) {
    public enum Kind { TEXT, HEADING, QUOTE, CODE, TABLE, RULE, COMPONENT }
    public enum Style { NORMAL, EMPHASIS, STRONG, CODE, REFERENCE }

    public record Run(String text, Style style, SemanticReference reference) {
        public Run {
            text = text == null ? "" : text;
            java.util.Objects.requireNonNull(style, "style");
            if ((style == Style.REFERENCE) != (reference != null)) {
                throw new IllegalArgumentException("semantic run reference is inconsistent");
            }
        }
    }

    public record Line(
            String nodeId,
            Kind kind,
            int indent,
            int height,
            List<Run> runs,
            RichComponent component) {
        public Line {
            if (nodeId == null || nodeId.isBlank() || indent < 0 || height <= 0) {
                throw new IllegalArgumentException("semantic line geometry is invalid");
            }
            java.util.Objects.requireNonNull(kind, "kind");
            runs = List.copyOf(runs);
            if ((kind == Kind.COMPONENT) != (component != null)) {
                throw new IllegalArgumentException("semantic component line is inconsistent");
            }
        }
    }

    public SemanticLayout {
        if (width <= 0 || height < 0) throw new IllegalArgumentException("invalid semantic layout");
        lines = List.copyOf(lines);
        narration = narration == null ? "" : narration;
        if (lines.stream().mapToInt(Line::height).sum() != height) {
            throw new IllegalArgumentException("semantic layout height is inconsistent");
        }
    }
}
