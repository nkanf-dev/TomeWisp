package dev.openallay.guide.semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/** Converts CommonMark into OpenAllay's closed, non-actionable semantic AST. */
public final class SemanticMessageParser {
    private static final java.util.UUID EMPTY_REQUEST = new java.util.UUID(0, 0);
    private final Parser parser = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();
    private final SemanticReferenceValidator references = new SemanticReferenceValidator();
    private final RichComponentRegistry components;

    public SemanticMessageParser() {
        this(RichComponentRegistry.builtins());
    }

    public SemanticMessageParser(RichComponentRegistry components) {
        this.components = Objects.requireNonNull(components, "components");
    }

    public SemanticDocument parse(String source) {
        return parse(source, SemanticReferenceIndex.empty(EMPTY_REQUEST));
    }

    public SemanticDocument parse(String source, SemanticReferenceIndex referenceIndex) {
        return parseFragment(source, 0, referenceIndex);
    }

    SemanticDocument parseFragment(String source, int firstBlockOrdinal) {
        return parseFragment(source, firstBlockOrdinal,
                SemanticReferenceIndex.empty(EMPTY_REQUEST));
    }

    SemanticDocument parseFragment(
            String source,
            int firstBlockOrdinal,
            SemanticReferenceIndex referenceIndex) {
        source = source == null ? "" : source;
        if (firstBlockOrdinal < 0) {
            throw new IllegalArgumentException("first block ordinal must not be negative");
        }
        Conversion conversion = new Conversion(
                source,
                firstBlockOrdinal,
                Objects.requireNonNull(referenceIndex, "referenceIndex"),
                references,
                components);
        Node document = parser.parse(source);
        List<SemanticBlock> blocks = conversion.blocks(document, "root");
        return SemanticDocument.of(blocks, conversion.diagnostics);
    }

    private static final class Conversion {
        private final String source;
        private final int firstBlockOrdinal;
        private final SemanticReferenceIndex referenceIndex;
        private final SemanticReferenceValidator referenceValidator;
        private final RichComponentRegistry componentRegistry;
        private final List<SemanticDiagnostic> diagnostics = new ArrayList<>();

        private Conversion(
                String source,
                int firstBlockOrdinal,
                SemanticReferenceIndex referenceIndex,
                SemanticReferenceValidator referenceValidator,
                RichComponentRegistry componentRegistry) {
            this.source = source;
            this.firstBlockOrdinal = firstBlockOrdinal;
            this.referenceIndex = referenceIndex;
            this.referenceValidator = referenceValidator;
            this.componentRegistry = componentRegistry;
        }

        private List<SemanticBlock> blocks(Node parent, String parentPath) {
            List<SemanticBlock> result = new ArrayList<>();
            int index = 0;
            for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
                String path = parentPath.equals("root")
                        ? "b" + (firstBlockOrdinal + index)
                        : parentPath + ".b" + index;
                SemanticBlock converted = block(node, path);
                if (converted != null) {
                    result.add(converted);
                    index++;
                }
            }
            return List.copyOf(result);
        }

        private SemanticBlock block(Node node, String path) {
            if (node instanceof Paragraph paragraph) {
                List<SemanticInline> content = inlines(paragraph, path);
                return new SemanticBlock.Paragraph(id(path, "paragraph", literal(node)), content);
            }
            if (node instanceof Heading heading) {
                List<SemanticInline> content = inlines(heading, path);
                return new SemanticBlock.Heading(
                        id(path, "heading", literal(node)), heading.getLevel(), content);
            }
            if (node instanceof BulletList list) {
                return list(node, path, false, 1);
            }
            if (node instanceof OrderedList list) {
                return list(node, path, true,
                        Objects.requireNonNullElse(list.getMarkerStartNumber(), 1));
            }
            if (node instanceof BlockQuote quote) {
                return new SemanticBlock.Quote(
                        id(path, "quote", literal(node)), blocks(quote, path));
            }
            if (node instanceof FencedCodeBlock code) {
                if ("openallay-component".equals(code.getInfo().strip())) {
                    return component(code, path);
                }
                return new SemanticBlock.CodeBlock(
                        id(path, "code_block", literal(node)), code.getInfo(), code.getLiteral());
            }
            if (node instanceof IndentedCodeBlock code) {
                return new SemanticBlock.CodeBlock(
                        id(path, "code_block", literal(node)), "", code.getLiteral());
            }
            if (node instanceof TableBlock table) {
                return table(table, path);
            }
            if (node instanceof ThematicBreak) {
                return new SemanticBlock.ThematicBreak(id(path, "thematic_break", literal(node)));
            }
            return unsafeBlock(node, path);
        }

        private SemanticBlock component(FencedCodeBlock code, String path) {
            String nodeId = id(path, "component", code.getLiteral());
            RichComponentRegistry.Decode decoded = componentRegistry.decode(
                    code.getLiteral(), nodeId, referenceIndex);
            if (decoded.successful()) {
                return new SemanticBlock.Component(nodeId, decoded.component());
            }
            diagnostics.add(new SemanticDiagnostic(decoded.failureCode(), nodeId));
            SemanticInline.Text fallback = new SemanticInline.Text(
                    id(path + ".s0", "text", decoded.fallbackText()), decoded.fallbackText());
            return new SemanticBlock.Paragraph(nodeId, List.of(fallback));
        }

        private SemanticBlock list(Node node, String path, boolean ordered, int start) {
            List<List<SemanticBlock>> items = new ArrayList<>();
            int index = 0;
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof ListItem item) {
                    items.add(blocks(item, path + ".i" + index++));
                } else {
                    items.add(List.of(unsafeBlock(child, path + ".i" + index++)));
                }
            }
            return new SemanticBlock.ListBlock(
                    id(path, ordered ? "ordered_list" : "bullet_list", literal(node)),
                    ordered,
                    Math.max(1, start),
                    items);
        }

        private SemanticBlock table(TableBlock table, String path) {
            SemanticBlock.TableRow header = new SemanticBlock.TableRow(List.of());
            List<SemanticBlock.TableRow> body = new ArrayList<>();
            int rowIndex = 0;
            for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
                for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                    if (!(row instanceof TableRow tableRow)) {
                        continue;
                    }
                    SemanticBlock.TableRow converted = tableRow(tableRow, path + ".r" + rowIndex++);
                    if (section instanceof TableHead && header.cells().isEmpty()) {
                        header = converted;
                    } else if (section instanceof TableBody) {
                        body.add(converted);
                    }
                }
            }
            return new SemanticBlock.Table(id(path, "table", literal(table)), header, body);
        }

        private SemanticBlock.TableRow tableRow(TableRow row, String path) {
            List<SemanticBlock.TableCell> cells = new ArrayList<>();
            int index = 0;
            for (Node node = row.getFirstChild(); node != null; node = node.getNext()) {
                if (node instanceof TableCell cell) {
                    cells.add(new SemanticBlock.TableCell(
                            alignment(cell.getAlignment()), inlines(cell, path + ".c" + index++)));
                }
            }
            return new SemanticBlock.TableRow(cells);
        }

        private static SemanticBlock.Alignment alignment(TableCell.Alignment alignment) {
            if (alignment == null) {
                return SemanticBlock.Alignment.NONE;
            }
            return switch (alignment) {
                case LEFT -> SemanticBlock.Alignment.LEFT;
                case CENTER -> SemanticBlock.Alignment.CENTER;
                case RIGHT -> SemanticBlock.Alignment.RIGHT;
            };
        }

        private List<SemanticInline> inlines(Node parent, String path) {
            List<SemanticInline> result = new ArrayList<>();
            int index = 0;
            for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
                String childPath = path + ".s" + index++;
                if (node instanceof Text text) {
                    result.addAll(text(text.getLiteral(), childPath));
                } else if (node instanceof Emphasis emphasis) {
                    result.add(new SemanticInline.Emphasis(
                            id(childPath, "emphasis", literal(node)), inlines(emphasis, childPath)));
                } else if (node instanceof StrongEmphasis strong) {
                    result.add(new SemanticInline.Strong(
                            id(childPath, "strong", literal(node)), inlines(strong, childPath)));
                } else if (node instanceof Code code) {
                    result.add(new SemanticInline.Code(
                            id(childPath, "code", code.getLiteral()), code.getLiteral()));
                } else if (node instanceof SoftLineBreak) {
                    result.add(new SemanticInline.Break(
                            id(childPath, "soft_break", "\n"), false));
                } else if (node instanceof HardLineBreak) {
                    result.add(new SemanticInline.Break(
                            id(childPath, "hard_break", "\n"), true));
                } else if (node instanceof Link || node instanceof Image || node instanceof HtmlInline) {
                    result.add(unsafeInline(node, childPath));
                } else {
                    result.add(unsafeInline(node, childPath));
                }
            }
            return List.copyOf(result);
        }

        private List<SemanticInline> text(String text, String path) {
            List<SemanticInline> result = new ArrayList<>();
            java.util.regex.Matcher matcher = referenceValidator.matcher(text);
            int offset = 0;
            int part = 0;
            while (matcher.find()) {
                if (matcher.start() > offset) {
                    String literal = text.substring(offset, matcher.start());
                    result.add(new SemanticInline.Text(
                            id(path + ".p" + part++, "text", literal), literal));
                }
                String token = matcher.group();
                SemanticReferenceValidator.Validation validated =
                        referenceValidator.validate(token, referenceIndex);
                String nodeId = id(path + ".p" + part++, "reference", token);
                if (validated.successful()) {
                    result.add(new SemanticInline.Reference(nodeId, validated.reference()));
                } else {
                    diagnostics.add(new SemanticDiagnostic(validated.failureCode(), nodeId));
                    result.add(new SemanticInline.Text(nodeId, token));
                }
                offset = matcher.end();
            }
            if (offset < text.length() || result.isEmpty()) {
                String literal = text.substring(offset);
                result.add(new SemanticInline.Text(
                        id(path + ".p" + part, "text", literal), literal));
            }
            return List.copyOf(result);
        }

        private SemanticInline unsafeInline(Node node, String path) {
            String literal = literal(node);
            String nodeId = id(path, "literal", literal);
            diagnostics.add(new SemanticDiagnostic("semantic_content_unsupported", nodeId));
            return new SemanticInline.Text(nodeId, literal);
        }

        private SemanticBlock unsafeBlock(Node node, String path) {
            String literal = literal(node);
            String nodeId = id(path, "literal_block", literal);
            diagnostics.add(new SemanticDiagnostic("semantic_content_unsupported", nodeId));
            SemanticInline.Text text = new SemanticInline.Text(
                    id(path + ".s0", "text", literal), literal);
            return new SemanticBlock.Paragraph(nodeId, List.of(text));
        }

        private String literal(Node node) {
            StringBuilder value = new StringBuilder();
            for (SourceSpan span : node.getSourceSpans()) {
                int start = Math.max(0, Math.min(source.length(), span.getInputIndex()));
                int end = Math.max(start, Math.min(source.length(), start + span.getLength()));
                value.append(source, start, end);
            }
            if (!value.isEmpty()) {
                return value.toString();
            }
            if (node instanceof HtmlBlock html) {
                return Objects.requireNonNullElse(html.getLiteral(), "");
            }
            if (node instanceof HtmlInline html) {
                return Objects.requireNonNullElse(html.getLiteral(), "");
            }
            return plainChildren(node);
        }

        private static String plainChildren(Node parent) {
            StringBuilder text = new StringBuilder();
            for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof Text literal) {
                    text.append(literal.getLiteral());
                } else if (child instanceof Code code) {
                    text.append(code.getLiteral());
                } else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) {
                    text.append('\n');
                } else {
                    text.append(plainChildren(child));
                }
            }
            return text.toString();
        }

        private static String id(String path, String kind, String content) {
            return SemanticIds.create(path, kind, Objects.requireNonNullElse(content, ""));
        }
    }
}
