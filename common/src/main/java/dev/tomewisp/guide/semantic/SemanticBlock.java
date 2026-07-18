package dev.tomewisp.guide.semantic;

import java.util.List;

/** Closed safe block subset translated from CommonMark. */
public sealed interface SemanticBlock
        permits SemanticBlock.Paragraph, SemanticBlock.Heading, SemanticBlock.ListBlock,
                SemanticBlock.Quote, SemanticBlock.CodeBlock, SemanticBlock.Table,
                SemanticBlock.ThematicBreak {
    String nodeId();

    record Paragraph(String nodeId, List<SemanticInline> content) implements SemanticBlock {
        public Paragraph {
            SemanticIds.require(nodeId);
            content = List.copyOf(content);
        }
    }

    record Heading(String nodeId, int level, List<SemanticInline> content)
            implements SemanticBlock {
        public Heading {
            SemanticIds.require(nodeId);
            if (level < 1 || level > 6) {
                throw new IllegalArgumentException("heading level must be between 1 and 6");
            }
            content = List.copyOf(content);
        }
    }

    record ListBlock(
            String nodeId,
            boolean ordered,
            int start,
            List<List<SemanticBlock>> items) implements SemanticBlock {
        public ListBlock {
            SemanticIds.require(nodeId);
            if (start < 1) {
                throw new IllegalArgumentException("list start must be positive");
            }
            items = items.stream().map(List::copyOf).toList();
        }
    }

    record Quote(String nodeId, List<SemanticBlock> content) implements SemanticBlock {
        public Quote {
            SemanticIds.require(nodeId);
            content = List.copyOf(content);
        }
    }

    record CodeBlock(String nodeId, String info, String code) implements SemanticBlock {
        public CodeBlock {
            SemanticIds.require(nodeId);
            info = info == null ? "" : info;
            code = code == null ? "" : code;
        }
    }

    record Table(
            String nodeId,
            TableRow header,
            List<TableRow> rows) implements SemanticBlock {
        public Table {
            SemanticIds.require(nodeId);
            java.util.Objects.requireNonNull(header, "header");
            rows = List.copyOf(rows);
        }
    }

    record TableRow(List<TableCell> cells) {
        public TableRow {
            cells = List.copyOf(cells);
        }
    }

    record TableCell(Alignment alignment, List<SemanticInline> content) {
        public TableCell {
            java.util.Objects.requireNonNull(alignment, "alignment");
            content = List.copyOf(content);
        }
    }

    enum Alignment { NONE, LEFT, CENTER, RIGHT }

    record ThematicBreak(String nodeId) implements SemanticBlock {
        public ThematicBreak {
            SemanticIds.require(nodeId);
        }
    }
}
