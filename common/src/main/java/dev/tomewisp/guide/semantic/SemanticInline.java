package dev.tomewisp.guide.semantic;

import java.util.List;

/** Closed inline subset; links, images, HTML, and actions are not representable. */
public sealed interface SemanticInline
        permits SemanticInline.Text, SemanticInline.Emphasis, SemanticInline.Strong,
                SemanticInline.Code, SemanticInline.Break, SemanticInline.Reference {
    String nodeId();

    record Text(String nodeId, String text) implements SemanticInline {
        public Text {
            SemanticIds.require(nodeId);
            text = text == null ? "" : text;
        }
    }

    record Emphasis(String nodeId, List<SemanticInline> children) implements SemanticInline {
        public Emphasis {
            SemanticIds.require(nodeId);
            children = List.copyOf(children);
        }
    }

    record Strong(String nodeId, List<SemanticInline> children) implements SemanticInline {
        public Strong {
            SemanticIds.require(nodeId);
            children = List.copyOf(children);
        }
    }

    record Code(String nodeId, String text) implements SemanticInline {
        public Code {
            SemanticIds.require(nodeId);
            text = text == null ? "" : text;
        }
    }

    record Break(String nodeId, boolean hard) implements SemanticInline {
        public Break {
            SemanticIds.require(nodeId);
        }
    }

    record Reference(String nodeId, SemanticReference reference) implements SemanticInline {
        public Reference {
            SemanticIds.require(nodeId);
            java.util.Objects.requireNonNull(reference, "reference");
        }
    }
}
