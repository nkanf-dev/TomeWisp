package dev.tomewisp.trace.minecraft;

import dev.tomewisp.tool.ToolResult;
import dev.tomewisp.trace.json.TraceParser;
import dev.tomewisp.trace.model.AgentTrace;
import java.io.IOException;
import java.io.Reader;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

public final class TraceRepository {
    @FunctionalInterface
    public interface ReaderOpener {
        Reader open() throws IOException;
    }

    public record TraceSource(String name, ReaderOpener opener) {
        public TraceSource {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Trace source name must not be blank");
            }
            Objects.requireNonNull(opener, "opener");
        }
    }

    public record LoadedTraces(Map<String, AgentTrace> traces) {
        public LoadedTraces {
            traces = Map.copyOf(new TreeMap<>(traces));
        }

        public List<String> ids() {
            return traces.keySet().stream().sorted().toList();
        }

        public Optional<AgentTrace> find(String id) {
            return Optional.ofNullable(traces.get(id));
        }
    }

    private final TraceParser parser;

    public TraceRepository(TraceParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    public ToolResult<LoadedTraces> load(Collection<TraceSource> sources) {
        TreeMap<String, AgentTrace> traces = new TreeMap<>();
        for (TraceSource source : sources.stream()
                .sorted(java.util.Comparator.comparing(TraceSource::name))
                .toList()) {
            String filenameId;
            try {
                filenameId = filenameId(source.name());
            } catch (IllegalArgumentException exception) {
                return new ToolResult.Failure<>("invalid_trace", exception.getMessage());
            }

            ToolResult<AgentTrace> parsed;
            try (Reader reader = source.opener().open()) {
                parsed = parser.parse(reader);
            } catch (IOException exception) {
                return new ToolResult.Failure<>(
                        "invalid_trace", "Unable to read " + source.name() + ": " + exception.getMessage());
            }
            if (parsed instanceof ToolResult.Failure<AgentTrace> failure) {
                return new ToolResult.Failure<>(
                        "invalid_trace", source.name() + ": " + failure.message());
            }

            AgentTrace trace = ((ToolResult.Success<AgentTrace>) parsed).value();
            if (!trace.id().equals(filenameId)) {
                return new ToolResult.Failure<>(
                        "invalid_trace",
                        source.name() + " declares id " + trace.id() + " but filename requires " + filenameId);
            }
            AgentTrace previous = traces.putIfAbsent(trace.id(), trace);
            if (previous != null) {
                return new ToolResult.Failure<>(
                        "invalid_trace", "Duplicate trace id from multiple resources: " + trace.id());
            }
        }
        return new ToolResult.Success<>(new LoadedTraces(traces));
    }

    private static String filenameId(String source) {
        int slash = source.lastIndexOf('/');
        String filename = source.substring(slash + 1);
        if (!filename.endsWith(".json") || filename.length() == ".json".length()) {
            throw new IllegalArgumentException("Trace resource must end in a named .json file: " + source);
        }
        return filename.substring(0, filename.length() - ".json".length());
    }
}
