package dev.openallay.guide.ui;

import dev.openallay.guide.semantic.RichComponent;
import dev.openallay.guide.semantic.SemanticBlock;
import dev.openallay.guide.semantic.SemanticReference;
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
            RichComponent component,
            TableBox table) {
        public Line(
                String nodeId,
                Kind kind,
                int indent,
                int height,
                List<Run> runs,
                RichComponent component) {
            this(nodeId, kind, indent, height, runs, component, null);
        }

        public Line {
            if (nodeId == null || nodeId.isBlank() || indent < 0 || height <= 0) {
                throw new IllegalArgumentException("semantic line geometry is invalid");
            }
            java.util.Objects.requireNonNull(kind, "kind");
            runs = List.copyOf(runs);
            if ((kind == Kind.COMPONENT) != (component != null)) {
                throw new IllegalArgumentException("semantic component line is inconsistent");
            }
            if ((kind == Kind.TABLE) != (table != null)) {
                throw new IllegalArgumentException("semantic table line is inconsistent");
            }
        }
    }

    /** Fully measured table geometry. Coordinates are relative to the table's top-left corner. */
    public record TableBox(
            Mode mode, int width, int height, int lineHeight, List<TableRow> rows) {
        public enum Mode { GRID, KEY_VALUE_CARDS }

        public TableBox {
            java.util.Objects.requireNonNull(mode, "mode");
            if (width <= 0 || height <= 0 || lineHeight <= 0) {
                throw new IllegalArgumentException("semantic table geometry is invalid");
            }
            rows = List.copyOf(rows);
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("semantic table must contain a row");
            }
        }
    }

    public record TableRow(boolean header, int y, int height, List<TableCell> cells) {
        public TableRow {
            if (y < 0 || height <= 0) {
                throw new IllegalArgumentException("semantic table row geometry is invalid");
            }
            cells = List.copyOf(cells);
            if (cells.isEmpty()) {
                throw new IllegalArgumentException("semantic table row must contain a cell");
            }
        }
    }

    public record TableCell(
            int column,
            int x,
            int y,
            int width,
            int height,
            SemanticBlock.Alignment alignment,
            List<CellLine> labelLines,
            List<CellLine> valueLines) {
        public TableCell {
            if (column < 0 || x < 0 || y < 0 || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("semantic table cell geometry is invalid");
            }
            java.util.Objects.requireNonNull(alignment, "alignment");
            labelLines = List.copyOf(labelLines);
            valueLines = List.copyOf(valueLines);
            if (valueLines.isEmpty()) {
                throw new IllegalArgumentException("semantic table cell value must contain a line");
            }
        }
    }

    public record CellLine(int y, int width, List<Run> runs) {
        public CellLine {
            if (y < 0 || width < 0) {
                throw new IllegalArgumentException("semantic table text geometry is invalid");
            }
            runs = List.copyOf(runs);
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
