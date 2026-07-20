package dev.openallay.resource.vfs;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** A virtual-path glob supporting segment-local {@code *}/{@code ?} and recursive {@code **}. */
public final class ResourceGlobPattern {
    private final String source;
    private final String mount;
    private final List<String> segments;
    private final List<Pattern> segmentPatterns;

    private ResourceGlobPattern(String source, String mount, List<String> segments, List<Pattern> segmentPatterns) {
        this.source = source;
        this.mount = mount;
        this.segments = List.copyOf(segments);
        this.segmentPatterns = List.copyOf(segmentPatterns);
    }

    public static ResourceGlobPattern compile(String source) {
        if (source == null || source.isBlank() || !source.startsWith("/") || source.endsWith("/")) {
            throw new IllegalArgumentException("Resource glob must be an absolute non-empty virtual path");
        }
        if (source.contains("//") || source.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Resource glob has an invalid segment");
        }
        String[] raw = source.substring(1).split("/", -1);
        if (raw.length == 0 || containsWildcard(raw[0])) {
            throw new IllegalArgumentException("Resource glob must select exactly one literal mount");
        }
        ResourcePath mountPath = ResourcePath.parse("/" + raw[0]);
        ArrayList<String> segments = new ArrayList<>(raw.length);
        ArrayList<Pattern> patterns = new ArrayList<>(raw.length);
        for (String segment : raw) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Resource glob has an invalid segment");
            }
            if (segment.equals("**")) {
                segments.add(segment);
                // The recursive branch does not consult this matcher, but keeping the
                // list total avoids a nullable compiled representation.
                patterns.add(Pattern.compile(".*"));
                continue;
            }
            StringBuilder regex = new StringBuilder("^");
            for (int index = 0; index < segment.length(); index++) {
                char value = segment.charAt(index);
                if (Character.isISOControl(value)) {
                    throw new IllegalArgumentException("Resource glob contains a forbidden character");
                }
                if (value == '*') regex.append(".*");
                else if (value == '?') regex.append('.');
                else regex.append(Pattern.quote(String.valueOf(value)));
            }
            regex.append('$');
            segments.add(segment);
            patterns.add(Pattern.compile(regex.toString()));
        }
        return new ResourceGlobPattern(source, mountPath.mount(), segments, patterns);
    }

    public String source() {
        return source;
    }

    public String mount() {
        return mount;
    }

    public boolean matches(ResourcePath path) {
        Objects.requireNonNull(path, "path");
        if (!path.mount().equals(mount)) return false;
        List<String> encoded = encodedSegments(path);
        return matches(0, 0, encoded);
    }

    private boolean matches(int patternIndex, int pathIndex, List<String> pathSegments) {
        if (patternIndex == segments.size()) return pathIndex == pathSegments.size();
        if (segments.get(patternIndex).equals("**")) {
            if (matches(patternIndex + 1, pathIndex, pathSegments)) return true;
            return pathIndex < pathSegments.size() && matches(patternIndex, pathIndex + 1, pathSegments);
        }
        return pathIndex < pathSegments.size()
                && segmentPatterns.get(patternIndex).matcher(pathSegments.get(pathIndex)).matches()
                && matches(patternIndex + 1, pathIndex + 1, pathSegments);
    }

    private static List<String> encodedSegments(ResourcePath path) {
        String rendered = path.toString().substring(1);
        return List.of(rendered.split("/", -1));
    }

    private static boolean containsWildcard(String value) {
        return value.indexOf('*') >= 0 || value.indexOf('?') >= 0;
    }
}
