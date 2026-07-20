package dev.openallay.resource.result;

import dev.openallay.resource.vfs.ResourcePath;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Opaque, invocation-scoped public identity of one published Tool result. */
public record ResourceResultId(String value) implements Comparable<ResourceResultId> {
    private static final Pattern FORMAT = Pattern.compile("r_[0-9a-f]{32}");

    public ResourceResultId {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid resource result ID");
        }
    }

    public static ResourceResultId create() {
        return new ResourceResultId("r_" + UUID.randomUUID().toString().replace("-", ""));
    }

    public static ResourceResultId fromPath(ResourcePath path) {
        Objects.requireNonNull(path, "path");
        if (path.segments().size() != 2 || !path.mount().equals("result")) {
            throw new IllegalArgumentException("Result path must be /result/<id>");
        }
        return new ResourceResultId(path.segments().get(1));
    }

    public ResourcePath path() {
        return ResourcePath.of("result", value);
    }

    @Override
    public int compareTo(ResourceResultId other) {
        return value.compareTo(other.value);
    }
}
