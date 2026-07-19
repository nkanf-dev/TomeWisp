package dev.openallay.guide.semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable block-prefix cache for one streaming assistant segment. */
public final class SemanticStreamingState {
    private final String source;
    private final String completedSource;
    private final List<SemanticBlock> completedBlocks;
    private final List<SemanticDiagnostic> completedDiagnostics;
    private final SemanticDocument document;

    private SemanticStreamingState(
            String source,
            String completedSource,
            List<SemanticBlock> completedBlocks,
            List<SemanticDiagnostic> completedDiagnostics,
            SemanticDocument document) {
        this.source = source;
        this.completedSource = completedSource;
        this.completedBlocks = List.copyOf(completedBlocks);
        this.completedDiagnostics = List.copyOf(completedDiagnostics);
        this.document = document;
    }

    public static SemanticStreamingState empty() {
        return new SemanticStreamingState("", "", List.of(), List.of(), SemanticDocument.empty());
    }

    public SemanticStreamingState update(
            String replacement, boolean terminal, SemanticMessageParser parser) {
        return update(
                replacement,
                terminal,
                parser,
                SemanticReferenceIndex.empty(new java.util.UUID(0, 0)));
    }

    public SemanticStreamingState update(
            String replacement,
            boolean terminal,
            SemanticMessageParser parser,
            SemanticReferenceIndex references) {
        replacement = replacement == null ? "" : replacement;
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(references, "references");
        int boundary = terminal ? replacement.length() : completedBoundary(replacement);
        List<SemanticBlock> nextCompleted = new ArrayList<>();
        List<SemanticDiagnostic> nextDiagnostics = new ArrayList<>();
        String nextCompletedSource = replacement.substring(0, boundary);

        if (boundary >= completedSource.length() && replacement.startsWith(completedSource)) {
            nextCompleted.addAll(completedBlocks);
            nextDiagnostics.addAll(completedDiagnostics);
            String appended = replacement.substring(completedSource.length(), boundary);
            if (!appended.isBlank()) {
                SemanticDocument parsed = parser.parseFragment(
                        appended, nextCompleted.size(), references);
                nextCompleted.addAll(parsed.blocks());
                nextDiagnostics.addAll(parsed.diagnostics());
            }
        } else if (!nextCompletedSource.isBlank()) {
            SemanticDocument parsed = parser.parseFragment(nextCompletedSource, 0, references);
            nextCompleted.addAll(parsed.blocks());
            nextDiagnostics.addAll(parsed.diagnostics());
        }

        String tail = replacement.substring(boundary);
        List<SemanticBlock> allBlocks = new ArrayList<>(nextCompleted);
        List<SemanticDiagnostic> allDiagnostics = new ArrayList<>(nextDiagnostics);
        if (!tail.isBlank()) {
            // A partial CommonMark block can change type and measured height on every byte.
            // Keep the mutable tail literal until completedBoundary validates a whole block.
            String path = "streaming-tail-" + allBlocks.size();
            SemanticInline.Text literal = new SemanticInline.Text(
                    SemanticIds.create(path + ".s0", "text", "literal"), tail);
            allBlocks.add(new SemanticBlock.Paragraph(
                    SemanticIds.create(path, "paragraph", "literal"), List.of(literal)));
        }
        return new SemanticStreamingState(
                replacement,
                nextCompletedSource,
                nextCompleted,
                nextDiagnostics,
                SemanticDocument.of(allBlocks, allDiagnostics));
    }

    public String source() {
        return source;
    }

    public List<SemanticBlock> completedBlocks() {
        return completedBlocks;
    }

    public SemanticDocument document() {
        return document;
    }

    private static int completedBoundary(String source) {
        int lastBoundary = 0;
        char fence = 0;
        int fenceLength = 0;
        int offset = 0;
        while (offset < source.length()) {
            int newline = source.indexOf('\n', offset);
            int end = newline < 0 ? source.length() : newline;
            String line = source.substring(offset, end);
            String stripped = line.stripLeading();
            int candidateLength = fenceLength(stripped);
            if (candidateLength >= 3) {
                char candidate = stripped.charAt(0);
                if (fence == 0) {
                    fence = candidate;
                    fenceLength = candidateLength;
                } else if (candidate == fence && candidateLength >= fenceLength) {
                    fence = 0;
                    fenceLength = 0;
                    if (newline >= 0) {
                        lastBoundary = newline + 1;
                    }
                }
            } else if (fence == 0 && line.isBlank() && newline >= 0) {
                lastBoundary = newline + 1;
            }
            if (newline < 0) {
                break;
            }
            offset = newline + 1;
        }
        return lastBoundary;
    }

    private static int fenceLength(String line) {
        if (line.isEmpty() || (line.charAt(0) != '`' && line.charAt(0) != '~')) {
            return 0;
        }
        char marker = line.charAt(0);
        int length = 0;
        while (length < line.length() && line.charAt(length) == marker) {
            length++;
        }
        return length;
    }
}
