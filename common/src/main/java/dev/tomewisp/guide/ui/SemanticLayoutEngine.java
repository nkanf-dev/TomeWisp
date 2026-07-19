package dev.tomewisp.guide.ui;

import dev.tomewisp.guide.semantic.RichComponent;
import dev.tomewisp.guide.semantic.SemanticBlock;
import dev.tomewisp.guide.semantic.SemanticDocument;
import dev.tomewisp.guide.semantic.SemanticInline;
import java.util.ArrayList;
import java.util.List;

/** Pure semantic block flattening and width-aware wrapping. */
public final class SemanticLayoutEngine {
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
                tableRow(value.nodeId() + "-head", value.header(), indent, width, measurer, output);
                int index = 0;
                for (SemanticBlock.TableRow row : value.rows()) {
                    tableRow(value.nodeId() + "-row-" + index++, row,
                            indent, width, measurer, output);
                }
            }
            case SemanticBlock.ThematicBreak value -> output.add(new SemanticLayout.Line(
                    value.nodeId(), SemanticLayout.Kind.RULE, indent,
                    measurer.lineHeight(SemanticLayout.Kind.RULE), List.of(), null));
            case SemanticBlock.Component value -> output.add(new SemanticLayout.Line(
                    value.nodeId(), SemanticLayout.Kind.COMPONENT, indent,
                    componentHeight(value.component(), measurer), List.of(), value.component()));
        }
    }

    private void tableRow(
            String nodeId,
            SemanticBlock.TableRow row,
            int indent,
            int width,
            Measurer measurer,
            List<SemanticLayout.Line> output) {
        ArrayList<SemanticLayout.Run> values = new ArrayList<>();
        for (int index = 0; index < row.cells().size(); index++) {
            if (index > 0) values.add(new SemanticLayout.Run(" | ", SemanticLayout.Style.CODE, null));
            values.addAll(runs(row.cells().get(index).content(), SemanticLayout.Style.NORMAL));
        }
        addWrapped(nodeId, SemanticLayout.Kind.TABLE, indent, values, width, measurer, output);
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
            case RichComponent.RecipeGrid ignored -> Math.max(54, line * 5);
            case RichComponent.IngredientCheck value -> Math.max(22, 22 * value.ingredients().size());
            case RichComponent.CraftabilitySummary ignored -> 40;
            case RichComponent.ProgressSteps value -> 12 * (value.steps().size() + 1);
            case RichComponent.SourceSummary value -> 12 * (value.sources().size() + 1);
            case RichComponent.StatusBadge ignored -> 16;
            case RichComponent.ChoiceGroup value -> 12 * (value.choices().size() + 1);
        };
    }
}
