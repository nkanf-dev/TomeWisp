package dev.openallay.resource.vfs;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Canonical absolute path in OpenAllay's logical resource namespace. */
public record ResourcePath(List<String> segments) implements Comparable<ResourcePath> {
    public ResourcePath {
        Objects.requireNonNull(segments, "segments");
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Resource path must name a mount");
        }
        ArrayList<String> copy = new ArrayList<>(segments.size());
        for (String segment : segments) {
            validateDecodedSegment(segment);
            copy.add(segment);
        }
        segments = Collections.unmodifiableList(copy);
    }

    public static ResourcePath parse(String value) {
        if (value == null || value.isBlank() || value.charAt(0) != '/') {
            throw new IllegalArgumentException("Resource path must be absolute");
        }
        if (value.length() == 1 || value.endsWith("/") || value.contains("//") || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Resource path has an invalid segment");
        }
        String[] encoded = value.substring(1).split("/", -1);
        ArrayList<String> decoded = new ArrayList<>(encoded.length);
        for (String segment : encoded) {
            decoded.add(decodeSegment(segment));
        }
        ResourcePath path = new ResourcePath(decoded);
        if (!path.toString().equals(value)) {
            throw new IllegalArgumentException("Resource path is not canonical");
        }
        return path;
    }

    public static ResourcePath of(String... segments) {
        return new ResourcePath(List.of(segments));
    }

    public String mount() {
        return segments.getFirst();
    }

    public ResourcePath child(String segment) {
        ArrayList<String> child = new ArrayList<>(segments);
        child.add(segment);
        return new ResourcePath(child);
    }

    public boolean startsWith(ResourcePath root) {
        return segments.size() >= root.segments.size()
                && segments.subList(0, root.segments.size()).equals(root.segments);
    }

    public ResourcePath parent() {
        if (segments.size() == 1) {
            throw new IllegalStateException("Mount root has no parent");
        }
        return new ResourcePath(segments.subList(0, segments.size() - 1));
    }

    @Override
    public int compareTo(ResourcePath other) {
        return toString().compareTo(other.toString());
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        for (String segment : segments) {
            result.append('/').append(encodeSegment(segment));
        }
        return result.toString();
    }

    private static void validateDecodedSegment(String segment) {
        if (segment == null || segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
            throw new IllegalArgumentException("Resource path has an invalid segment");
        }
        for (int index = 0; index < segment.length(); index++) {
            char value = segment.charAt(index);
            if (value == '\\' || value == 0 || Character.isISOControl(value)) {
                throw new IllegalArgumentException("Resource path contains a forbidden character");
            }
        }
    }

    private static String encodeSegment(String value) {
        StringBuilder result = new StringBuilder();
        for (byte current : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = current & 0xff;
            if ((unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9')
                    || unsigned == '-' || unsigned == '_' || unsigned == '.' || unsigned == '@') {
                result.append((char) unsigned);
            } else {
                result.append('%');
                result.append(Character.toUpperCase(Character.forDigit(unsigned >>> 4, 16)));
                result.append(Character.toUpperCase(Character.forDigit(unsigned & 0xf, 16)));
            }
        }
        return result.toString();
    }

    private static String decodeSegment(String value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(value.length());
        for (int index = 0; index < value.length();) {
            char current = value.charAt(index);
            if (current == '%') {
                if (index + 2 >= value.length()) {
                    throw new IllegalArgumentException("Invalid resource path escape");
                }
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("Invalid resource path escape");
                }
                output.write((high << 4) | low);
                index += 3;
            } else {
                if (current > 0x7f) {
                    byte[] bytes = String.valueOf(current).getBytes(StandardCharsets.UTF_8);
                    output.writeBytes(bytes);
                } else {
                    output.write((byte) current);
                }
                index++;
            }
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
