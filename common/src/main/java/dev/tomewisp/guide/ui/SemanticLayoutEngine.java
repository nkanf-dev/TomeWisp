package dev.tomewisp.guide.ui;

import dev.tomewisp.guide.semantic.RichComponent;
import dev.tomewisp.guide.semantic.SemanticBlock;
import dev.tomewisp.guide.semantic.SemanticDocument;
import dev.tomewisp.guide.semantic.SemanticInline;
import java.util.ArrayList;
import java.util.List;

/** Pure semantic block flattening and width-aware wrapping. */
public final class SemanticLayoutEngine {
    private static final int TABLE_BORDER = 1;
    private static final int TABLE_PADDING_X = 4;
    private static final int TABLE_PADDING_Y = 3;
    private static final int MIN_GRID_COLUMN_WIDTH = 48;
    private static final int CARD_GAP = 4;

    public interface Measurer {
        int width(String text, SemanticLayout.Style style);
        int lineHeight(SemanticLayout.Kind kind);
    }

    public SemanticLayout layout(SemanticDocument document, int width, Measurer measurer) {
        java.util.Objects.requireNonNull(document, "document");
        java.util.Objects.requireNonNull(measurer, "measurer");
        if (width <= 0) throw new IllegalArgumentException("semantic layout width must be positive");
        ArrayList<SemanticLayout.Line> lines = new ArrayList<>();
        for (SemanticBlock block : document.blocks()) flatten(block, 0, width, measurer, lines);
        return new SemanticLayout(
                width,
                lines.stream().mapToInt(SemanticLayout.Line::height).sum(),
                lines,
                document.fallbackText());
    }

    private void flatten(
            SemanticBlock block,
            int indent,
            int width,
            Measurer measurer,
            List<SemanticLayout.Line> output) {
        switch (block) {
            case SemanticBlock.Paragraph value -> addWrapped(
                    value.nodeId(), SemanticLayout.Kind.TEXT, indent,
                    runs(value.content(), SemanticLayout.Style.NORMAL), width, measurer, output);
            case SemanticBlock.Heading value -> addWrapped(
                    value.nodeId(), SemanticLayout.Kind.HEADING, indent,
                    runs(value.content(), SemanticLayout.Style.STRONG), width, measurer, output);
            case SemanticBlock.Quote value -> {
                int from = output.size();
                value.content().forEach(child -> flatten(child, indent + 8, width, measurer, output));
                for (int index = from; index < output.size(); index++) {
                    SemanticLayout.Line line = output.get(index);
                    if (line.kind() != SemanticLayout.Kind.COMPONENT) {
                        output.set(index, new SemanticLayout.Line(
                                line.nodeId(), SemanticLayout.Kind.QUOTE, line.indent(),
                                line.height(), line.runs(), null));
                    }
                }
            }
            case SemanticBlock.CodeBlock value -> {
                String[] codeLines = value.code().split("\\R", -1);
                for (int index = 0; index < codeLines.length; index++) {
                    addWrapped(value.nodeId() + "-" + index, SemanticLayout.Kind.CODE, indent + 4,
                            List.of(new SemanticLayout.Run(
                                    codeLines[index], SemanticLayout.Style.CODE, null)),
                            width, measurer, output);
                }
            }
            case SemanticBlock.ListBlock value -> {
                int number = value.start();
                int itemIndex = 0;
                for (List<SemanticBlock> item : value.items()) {
                    String marker = value.ordered() ? number++ + ". " : "• ";
                    int contentIndent = indent + Math.max(
                            1, measurer.width(marker, SemanticLayout.Style.STRONG));
                    int firstParagraph = firstParagraph(item);
                    if (firstParagraph < 0) {
                        output.add(new SemanticLayout.Line(
                                value.nodeId() + "-marker-" + itemIndex,
                                SemanticLayout.Kind.TEXT,
                                indent,
                                measurer.lineHeight(SemanticLayout.Kind.TEXT),
                                List.of(new SemanticLayout.Run(
                                        marker, SemanticLayout.Style.STRONG, null)),
                                null));
                    }
                    for (int childIndex = 0; childIndex < item.size(); childIndex++) {
                        SemanticBlock child = item.get(childIndex);
                        if (childIndex == firstParagraph) {
                            SemanticBlock.Paragraph paragraph = (SemanticBlock.Paragraph) child;
                            ArrayList<SemanticLayout.Run> marked = new ArrayList<>();
                            marked.add(new SemanticLayout.Run(
                                    marker, SemanticLayout.Style.STRONG, null));
                            marked.addAll(runs(paragraph.content(), SemanticLayout.Style.NORMAL));
                            addWrapped(
                                    paragraph.nodeId(), SemanticLayout.Kind.TEXT,
                                    indent, contentIndent, marked, width, measurer, output);
                        } else {
                            flatten(child, contentIndent, width, measurer, output);
                        }
                    }
                    itemIndex++;
                }
            }
            case SemanticBlock.Table value -> {
                SemanticLayout.TableBox table = table(value, Math.max(1, width - indent), measurer);
                output.add(new SemanticLayout.Line(
                        value.nodeId(), SemanticLayout.Kind.TABLE, indent,
                        table.height(), List.of(), null, table));
            }
            case SemanticBlock.ThematicBreak value -> output.add(new SemanticLayout.Line(
                    value.nodeId(), SemanticLayout.Kind.RULE, indent,
                    measurer.lineHeight(SemanticLayout.Kind.RULE), List.of(), null));
            case SemanticBlock.Component value -> output.add(new SemanticLayout.Line(
                    value.nodeId(), SemanticLayout.Kind.COMPONENT, indent,
                    componentHeight(value.component(), measurer), List.of(), value.component()));
        }
    }

    private SemanticLayout.TableBox table(
            SemanticBlock.Table table,
            int width,
            Measurer measurer) {
        int columns = Math.max(
                table.header().cells().size(),
                table.rows().stream().mapToInt(row -> row.cells().size()).max().orElse(0));
        if (columns == 0) {
            throw new IllegalArgumentException("semantic table has no columns");
        }
        return width >= columns * MIN_GRID_COLUMN_WIDTH
                ? gridTable(table, columns, width, measurer)
                : cardTable(table, columns, width, measurer);
    }

    private SemanticLayout.TableBox gridTable(
            SemanticBlock.Table table,
            int columns,
            int availableWidth,
            Measurer measurer) {
        List<SemanticBlock.TableRow> sourceRows = new ArrayList<>();
        sourceRows.add(table.header());
        sourceRows.addAll(table.rows());
        int[] desired = new int[columns];
        java.util.Arrays.fill(desired, MIN_GRID_COLUMN_WIDTH);
        for (SemanticBlock.TableRow row : sourceRows) {
            for (int column = 0; column < row.cells().size(); column++) {
                List<SemanticLayout.Run> values = runs(
                        row.cells().get(column).content(), SemanticLayout.Style.NORMAL);
                desired[column] = Math.max(desired[column],
                        runWidth(values, measurer) + TABLE_PADDING_X * 2 + TABLE_BORDER);
            }
        }
        int[] widths = distributeColumns(desired, availableWidth);
        int lineHeight = measurer.lineHeight(SemanticLayout.Kind.TABLE);
        ArrayList<SemanticLayout.TableRow> rows = new ArrayList<>();
        int rowY = 0;
        for (int rowIndex = 0; rowIndex < sourceRows.size(); rowIndex++) {
            SemanticBlock.TableRow source = sourceRows.get(rowIndex);
            boolean header = rowIndex == 0;
            ArrayList<List<SemanticLayout.CellLine>> wrapped = new ArrayList<>();
            int rowHeight = lineHeight + TABLE_PADDING_Y * 2 + TABLE_BORDER;
            for (int column = 0; column < columns; column++) {
                SemanticBlock.TableCell cell = cell(source, column);
                List<SemanticLayout.CellLine> lines = cellLines(
                        cell.content(),
                        header ? SemanticLayout.Style.STRONG : SemanticLayout.Style.NORMAL,
                        Math.max(1, widths[column] - TABLE_PADDING_X * 2 - TABLE_BORDER),
                        lineHeight,
                        measurer);
                wrapped.add(lines);
                rowHeight = Math.max(rowHeight,
                        lines.size() * lineHeight + TABLE_PADDING_Y * 2 + TABLE_BORDER);
            }
            ArrayList<SemanticLayout.TableCell> cells = new ArrayList<>();
            int cellX = 0;
            for (int column = 0; column < columns; column++) {
                SemanticBlock.TableCell sourceCell = cell(source, column);
                cells.add(new SemanticLayout.TableCell(
                        column, cellX, rowY, widths[column], rowHeight,
                        sourceCell.alignment(), List.of(), wrapped.get(column)));
                cellX += widths[column];
            }
            rows.add(new SemanticLayout.TableRow(header, rowY, rowHeight, cells));
            rowY += rowHeight;
        }
        return new SemanticLayout.TableBox(
                SemanticLayout.TableBox.Mode.GRID,
                java.util.Arrays.stream(widths).sum(), rowY, lineHeight, rows);
    }

    private SemanticLayout.TableBox cardTable(
            SemanticBlock.Table table,
            int columns,
            int width,
            Measurer measurer) {
        int lineHeight = measurer.lineHeight(SemanticLayout.Kind.TABLE);
        List<SemanticBlock.TableRow> dataRows = table.rows().isEmpty()
                ? List.of(table.header()) : table.rows();
        ArrayList<SemanticLayout.TableRow> rows = new ArrayList<>();
        int cardY = 0;
        for (int rowIndex = 0; rowIndex < dataRows.size(); rowIndex++) {
            SemanticBlock.TableRow source = dataRows.get(rowIndex);
            ArrayList<SemanticLayout.TableCell> cells = new ArrayList<>();
            int cellY = cardY + TABLE_PADDING_Y + TABLE_BORDER;
            for (int column = 0; column < columns; column++) {
                SemanticBlock.TableCell value = cell(source, column);
                SemanticBlock.TableCell header = cell(table.header(), column);
                int innerWidth = Math.max(1, width - TABLE_PADDING_X * 2 - TABLE_BORDER * 2);
                List<SemanticLayout.CellLine> labels = cellLines(
                        header.content(), SemanticLayout.Style.STRONG,
                        innerWidth, lineHeight, measurer);
                List<SemanticLayout.CellLine> values = cellLines(
                        value.content(), SemanticLayout.Style.NORMAL,
                        innerWidth, lineHeight, measurer);
                int cellHeight = (labels.size() + values.size()) * lineHeight + TABLE_PADDING_Y;
                cells.add(new SemanticLayout.TableCell(
                        column,
                        TABLE_BORDER + TABLE_PADDING_X,
                        cellY,
                        innerWidth,
                        cellHeight,
                        value.alignment(),
                        labels,
                        values));
                cellY += cellHeight;
            }
            int cardHeight = cellY - cardY + TABLE_PADDING_Y;
            rows.add(new SemanticLayout.TableRow(false, cardY, cardHeight, cells));
            cardY += cardHeight + (rowIndex + 1 < dataRows.size() ? CARD_GAP : 0);
        }
        return new SemanticLayout.TableBox(
                SemanticLayout.TableBox.Mode.KEY_VALUE_CARDS,
                width, cardY, lineHeight, rows);
    }

    private static SemanticBlock.TableCell cell(SemanticBlock.TableRow row, int column) {
        return column < row.cells().size()
                ? row.cells().get(column)
                : new SemanticBlock.TableCell(SemanticBlock.Alignment.NONE, List.of());
    }

    private static int[] distributeColumns(int[] desired, int available) {
        int[] widths = new int[desired.length];
        java.util.Arrays.fill(widths, MIN_GRID_COLUMN_WIDTH);
        int remaining = available - MIN_GRID_COLUMN_WIDTH * desired.length;
        int totalExtra = java.util.Arrays.stream(desired)
                .map(value -> Math.max(0, value - MIN_GRID_COLUMN_WIDTH)).sum();
        for (int index = 0; index < widths.length && remaining > 0; index++) {
            int extra = totalExtra == 0
                    ? remaining / (widths.length - index)
                    : (int) ((long) remaining
                            * Math.max(0, desired[index] - MIN_GRID_COLUMN_WIDTH)
                            / totalExtra);
            extra = Math.min(remaining, extra);
            widths[index] += extra;
            remaining -= extra;
            totalExtra -= Math.max(0, desired[index] - MIN_GRID_COLUMN_WIDTH);
        }
        if (remaining > 0) widths[widths.length - 1] += remaining;
        return widths;
    }

    private static List<SemanticLayout.CellLine> cellLines(
            List<SemanticInline> content,
            SemanticLayout.Style inherited,
            int width,
            int lineHeight,
            Measurer measurer) {
        List<List<SemanticLayout.Run>> wrapped = wrapRuns(runs(content, inherited), width, measurer);
        ArrayList<SemanticLayout.CellLine> result = new ArrayList<>();
        for (int index = 0; index < wrapped.size(); index++) {
            List<SemanticLayout.Run> line = wrapped.get(index);
            result.add(new SemanticLayout.CellLine(
                    index * lineHeight, runWidth(line, measurer), line));
        }
        return List.copyOf(result);
    }

    private static int runWidth(List<SemanticLayout.Run> runs, Measurer measurer) {
        return runs.stream().mapToInt(run -> measurer.width(run.text(), run.style())).sum();
    }

    private static List<List<SemanticLayout.Run>> wrapRuns(
            List<SemanticLayout.Run> runs, int width, Measurer measurer) {
        ArrayList<List<SemanticLayout.Run>> output = new ArrayList<>();
        ArrayList<SemanticLayout.Run> line = new ArrayList<>();
        int used = 0;
        for (SemanticLayout.Run run : runs) {
            StringBuilder chunk = new StringBuilder();
            for (int offset = 0; offset < run.text().length();) {
                int codePoint = run.text().codePointAt(offset);
                String value = new String(Character.toChars(codePoint));
                if (codePoint == '\n') {
                    flushChunk(line, chunk, run);
                    output.add(List.copyOf(line));
                    line = new ArrayList<>();
                    used = 0;
                    offset += Character.charCount(codePoint);
                    continue;
                }
                int valueWidth = Math.max(1, measurer.width(value, run.style()));
                if (used + valueWidth > width && (!line.isEmpty() || !chunk.isEmpty())) {
                    flushChunk(line, chunk, run);
                    output.add(List.copyOf(line));
                    line = new ArrayList<>();
                    used = 0;
                }
                chunk.append(value);
                used += valueWidth;
                offset += Character.charCount(codePoint);
            }
            flushChunk(line, chunk, run);
        }
        if (!line.isEmpty() || output.isEmpty()) output.add(List.copyOf(line));
        return List.copyOf(output);
    }

    private static void flushChunk(
            List<SemanticLayout.Run> line,
            StringBuilder chunk,
            SemanticLayout.Run source) {
        if (chunk.isEmpty()) return;
        line.add(new SemanticLayout.Run(
                chunk.toString(), source.style(), source.reference()));
        chunk.setLength(0);
    }

    private void addWrapped(
            String nodeId,
            SemanticLayout.Kind kind,
            int indent,
            List<SemanticLayout.Run> runs,
            int width,
            Measurer measurer,
            List<SemanticLayout.Line> output) {
        addWrapped(nodeId, kind, indent, indent, runs, width, measurer, output);
    }

    private void addWrapped(
            String nodeId,
            SemanticLayout.Kind kind,
            int firstIndent,
            int continuationIndent,
            List<SemanticLayout.Run> runs,
            int width,
            Measurer measurer,
            List<SemanticLayout.Line> output) {
        int indent = firstIndent;
        int available = Math.max(1, width - indent);
        int initialOutputSize = output.size();
        ArrayList<SemanticLayout.Run> line = new ArrayList<>();
        int used = 0;
        int lineIndex = 0;
        for (SemanticLayout.Run run : runs) {
            StringBuilder chunk = new StringBuilder();
            for (int offset = 0; offset < run.text().length();) {
                int codePoint = run.text().codePointAt(offset);
                String value = new String(Character.toChars(codePoint));
                if (codePoint == '\n') {
                    if (!chunk.isEmpty()) {
                        line.add(new SemanticLayout.Run(
                                chunk.toString(), run.style(), run.reference()));
                        chunk.setLength(0);
                    }
                    output.add(line(nodeId, lineIndex++, kind, indent, measurer, line));
                    line = new ArrayList<>();
                    used = 0;
                    indent = continuationIndent;
                    available = Math.max(1, width - indent);
                    offset += Character.charCount(codePoint);
                    continue;
                }
                int valueWidth = Math.max(1, measurer.width(value, run.style()));
                if (used + valueWidth > available && (!line.isEmpty() || !chunk.isEmpty())) {
                    if (!chunk.isEmpty()) {
                        line.add(new SemanticLayout.Run(
                                chunk.toString(), run.style(), run.reference()));
                        chunk.setLength(0);
                    }
                    output.add(line(nodeId, lineIndex++, kind, indent, measurer, line));
                    line = new ArrayList<>();
                    used = 0;
                    indent = continuationIndent;
                    available = Math.max(1, width - indent);
                }
                chunk.append(value);
                used += valueWidth;
                offset += Character.charCount(codePoint);
            }
            if (!chunk.isEmpty()) line.add(new SemanticLayout.Run(
                    chunk.toString(), run.style(), run.reference()));
        }
        if (!line.isEmpty() || output.size() == initialOutputSize) {
            output.add(line(nodeId, lineIndex, kind, indent, measurer, line));
        }
    }

    private static int firstParagraph(List<SemanticBlock> item) {
        for (int index = 0; index < item.size(); index++) {
            if (item.get(index) instanceof SemanticBlock.Paragraph) return index;
        }
        return -1;
    }

    private static SemanticLayout.Line line(
            String nodeId,
            int lineIndex,
            SemanticLayout.Kind kind,
            int indent,
            Measurer measurer,
            List<SemanticLayout.Run> runs) {
        return new SemanticLayout.Line(
                nodeId + "-line-" + lineIndex, kind, indent,
                measurer.lineHeight(kind), runs, null);
    }

    private static List<SemanticLayout.Run> runs(
            List<SemanticInline> inlines, SemanticLayout.Style inherited) {
        ArrayList<SemanticLayout.Run> result = new ArrayList<>();
        for (SemanticInline inline : inlines) {
            switch (inline) {
                case SemanticInline.Text value -> result.add(new SemanticLayout.Run(
                        value.text(), inherited, null));
                case SemanticInline.Code value -> result.add(new SemanticLayout.Run(
                        value.text(), SemanticLayout.Style.CODE, null));
                case SemanticInline.Break ignored -> result.add(new SemanticLayout.Run(
                        "\n", inherited, null));
                case SemanticInline.Emphasis value -> result.addAll(runs(
                        value.children(), SemanticLayout.Style.EMPHASIS));
                case SemanticInline.Strong value -> result.addAll(runs(
                        value.children(), SemanticLayout.Style.STRONG));
                case SemanticInline.Reference value -> result.add(new SemanticLayout.Run(
                        value.reference().displayText(), SemanticLayout.Style.REFERENCE,
                        value.reference()));
            }
        }
        return List.copyOf(result);
    }

    private static int componentHeight(RichComponent component, Measurer measurer) {
        int line = measurer.lineHeight(SemanticLayout.Kind.COMPONENT);
        return switch (component) {
            case RichComponent.ItemRow value -> Math.max(22, 22 * value.items().size());
            case RichComponent.RecipeGrid ignored -> Math.max(136, line * 13);
            case RichComponent.IngredientCheck value -> Math.max(22, 22 * value.ingredients().size());
            case RichComponent.CraftabilitySummary ignored -> 40;
            case RichComponent.ProgressSteps value -> 12 * (value.steps().size() + 1);
            case RichComponent.SourceSummary value -> 12 * (value.sources().size() + 1);
            case RichComponent.StatusBadge ignored -> 16;
            case RichComponent.ChoiceGroup value -> 12 * (value.choices().size() + 1);
        };
    }
}
