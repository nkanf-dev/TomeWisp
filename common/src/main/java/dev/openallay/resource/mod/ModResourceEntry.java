package dev.openallay.resource.mod;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** A detached, public logical resource contributed by one installed mod. */
public final class ModResourceEntry {
    private static final Pattern MOD_OR_NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Set<String> PUBLIC_METADATA = Set.of(
            "fabric.mod.json",
            "quilt.mod.json",
            "pack.mcmeta",
            "META-INF/mods.toml",
            "META-INF/neoforge.mods.toml");
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "cfg", "csv", "fsh", "glsl", "json", "json5", "lang", "mcfunction", "mcmeta",
            "md", "properties", "srg", "toml", "tsv", "txt", "vsh", "xml", "yaml", "yml");
    private static final Set<String> EXECUTABLE_OR_SECRET_EXTENSIONS = Set.of(
            "bat", "class", "cmd", "dylib", "exe", "jar", "jks", "jnilib", "key", "p12",
            "pem", "ps1", "sh", "so");

    private final String modId;
    private final PublicLocation location;
    private final ContentKind contentKind;
    private final long size;
    private final String sha256;
    private final String mediaType;
    private final int precedence;
    private final String sourceId;
    private final DetachedContent content;
    private final Disposition disposition;

    private ModResourceEntry(
            String modId,
            PublicLocation location,
            ContentKind contentKind,
            long size,
            String sha256,
            String mediaType,
            int precedence,
            String sourceId,
            DetachedContent content,
            Disposition disposition) {
        this.modId = validateName(modId, "modId");
        this.location = Objects.requireNonNull(location, "location");
        this.contentKind = Objects.requireNonNull(contentKind, "contentKind");
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative");
        }
        this.size = size;
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be a lowercase SHA-256 digest");
        }
        this.sha256 = sha256;
        if (mediaType == null || mediaType.isBlank()) {
            throw new IllegalArgumentException("mediaType is required");
        }
        this.mediaType = mediaType;
        this.precedence = precedence;
        this.sourceId = validateSourceId(sourceId);
        this.content = Objects.requireNonNull(content, "content");
        this.disposition = Objects.requireNonNull(disposition, "disposition");
    }

    public static ModResourceEntry capture(
            String modId,
            PublicLocation location,
            InputStream input,
            long declaredSize,
            int precedence,
            String sourceId) throws IOException {
        Objects.requireNonNull(input, "input");
        ContentKind kind = contentKind(location.logicalPath());
        String mediaType = mediaType(location.logicalPath(), kind);
        if (kind == ContentKind.BINARY_METADATA) {
            DigestResult digest = digest(input);
            if (declaredSize >= 0 && declaredSize != digest.size()) {
                throw new IOException("Resource size changed while it was captured");
            }
            return new ModResourceEntry(
                    modId, location, kind, digest.size(), digest.sha256(), mediaType, precedence, sourceId,
                    DetachedContent.binary(), Disposition.CANDIDATE);
        }
        byte[] bytes = input.readAllBytes();
        if (declaredSize >= 0 && declaredSize != bytes.length) {
            throw new IOException("Resource size changed while it was captured");
        }
        DetachedContent text = DetachedContent.text(bytes);
        return new ModResourceEntry(
                modId, location, kind, bytes.length, sha256(bytes), mediaType, precedence, sourceId,
                text, Disposition.CANDIDATE);
    }

    ModResourceEntry withDisposition(Disposition value) {
        return new ModResourceEntry(
                modId, location, contentKind, size, sha256, mediaType, precedence, sourceId, content, value);
    }

    public String modId() {
        return modId;
    }

    public PublicLocation location() {
        return location;
    }

    public ContentKind contentKind() {
        return contentKind;
    }

    public long size() {
        return size;
    }

    public String sha256() {
        return sha256;
    }

    public String mediaType() {
        return mediaType;
    }

    public int precedence() {
        return precedence;
    }

    public String sourceId() {
        return sourceId;
    }

    public Disposition disposition() {
        return disposition;
    }

    public Optional<String> text() {
        return content.text();
    }

    String logicalIdentity() {
        return modId + '\u0000' + location.area() + '\u0000' + location.namespace() + '\u0000'
                + location.logicalPath();
    }

    private static String validateName(String value, String name) {
        if (value == null || !MOD_OR_NAMESPACE.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is not a logical Minecraft identifier");
        }
        return value;
    }

    private static String validateSourceId(String value) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.indexOf('\\') >= 0
                || value.startsWith("file:") || value.startsWith("jar:") || !value.contains(":")) {
            throw new IllegalArgumentException("sourceId must be a logical provenance identifier");
        }
        return value;
    }

    private static ContentKind contentKind(String path) {
        String extension = extension(path);
        if (extension.equals("json") || extension.equals("json5") || extension.equals("mcmeta")) {
            return ContentKind.STRUCTURED_TEXT;
        }
        return TEXT_EXTENSIONS.contains(extension) ? ContentKind.TEXT : ContentKind.BINARY_METADATA;
    }

    private static String mediaType(String path, ContentKind kind) {
        String extension = extension(path);
        return switch (extension) {
            case "json", "mcmeta" -> "application/json";
            case "json5" -> "application/json5";
            case "toml" -> "application/toml";
            case "yaml", "yml" -> "application/yaml";
            case "xml" -> "application/xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "ogg" -> "audio/ogg";
            default -> kind == ContentKind.BINARY_METADATA
                    ? "application/octet-stream"
                    : "text/plain; charset=utf-8";
        };
    }

    private static String extension(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digestInstance().digest(bytes));
    }

    private static DigestResult digest(InputStream input) throws IOException {
        MessageDigest digest = digestInstance();
        byte[] buffer = new byte[8192];
        long size = 0;
        for (int read; (read = input.read(buffer)) >= 0;) {
            if (read == 0) {
                continue;
            }
            digest.update(buffer, 0, read);
            size += read;
        }
        return new DigestResult(size, HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest digestInstance() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK does not provide SHA-256", exception);
        }
    }

    public enum Area {
        ASSETS("assets"), DATA("data"), METADATA("metadata");

        private final String pathSegment;

        Area(String pathSegment) {
            this.pathSegment = pathSegment;
        }

        public String pathSegment() {
            return pathSegment;
        }
    }

    public enum ContentKind {
        TEXT, STRUCTURED_TEXT, BINARY_METADATA
    }

    public enum Disposition {
        CANDIDATE, ACTIVE, SHADOWED
    }

    public record PublicLocation(Area area, String namespace, String logicalPath) {
        public PublicLocation {
            Objects.requireNonNull(area, "area");
            if (area == Area.METADATA) {
                if (!namespace.isEmpty() || !PUBLIC_METADATA.contains(logicalPath)) {
                    throw new IllegalArgumentException("metadata location is not allowlisted");
                }
            } else {
                namespace = validateName(namespace, "namespace");
                validateResourcePath(logicalPath);
            }
        }

        public static Optional<PublicLocation> parse(String rawPath) {
            if (!isSafeRelative(rawPath)) {
                return Optional.empty();
            }
            if (PUBLIC_METADATA.contains(rawPath)) {
                return Optional.of(new PublicLocation(Area.METADATA, "", rawPath));
            }
            String[] parts = rawPath.split("/", 3);
            if (parts.length != 3 || !MOD_OR_NAMESPACE.matcher(parts[1]).matches()) {
                return Optional.empty();
            }
            Area area = switch (parts[0]) {
                case "assets" -> Area.ASSETS;
                case "data" -> Area.DATA;
                default -> null;
            };
            if (area == null || !isSafeContentPath(parts[2])) {
                return Optional.empty();
            }
            return Optional.of(new PublicLocation(area, parts[1], parts[2]));
        }

        private static void validateResourcePath(String value) {
            if (!isSafeContentPath(value)) {
                throw new IllegalArgumentException("logicalPath is unsafe");
            }
        }

        private static boolean isSafeRelative(String value) {
            if (value == null || value.isBlank() || value.startsWith("/") || value.endsWith("/")
                    || value.indexOf('\\') >= 0 || value.contains("//")) {
                return false;
            }
            for (String segment : value.split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                        || segment.equalsIgnoreCase(".env") || segment.equalsIgnoreCase("credentials")
                        || segment.equalsIgnoreCase("secrets")) {
                    return false;
                }
                for (int index = 0; index < segment.length(); index++) {
                    if (Character.isISOControl(segment.charAt(index))) {
                        return false;
                    }
                }
            }
            return true;
        }

        private static boolean isSafeContentPath(String path) {
            if (!isSafeRelative(path)) {
                return false;
            }
            String lower = path.toLowerCase(Locale.ROOT);
            String extension = extension(lower);
            if (EXECUTABLE_OR_SECRET_EXTENSIONS.contains(extension)) {
                return false;
            }
            String name = lower.substring(lower.lastIndexOf('/') + 1);
            return !name.endsWith(".rsa") && !name.endsWith(".dsa") && !name.endsWith(".sf");
        }
    }

    private sealed interface DetachedContent permits DetachedText, BinaryOnly {
        Optional<String> text();

        static DetachedContent text(byte[] bytes) {
            return new DetachedText(bytes);
        }

        static DetachedContent binary() {
            return BinaryOnly.INSTANCE;
        }
    }

    private static final class DetachedText implements DetachedContent {
        private final byte[] bytes;
        private volatile String decoded;

        private DetachedText(byte[] bytes) {
            this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        @Override
        public Optional<String> text() {
            String value = decoded;
            if (value == null) {
                value = decodeUtf8(bytes);
                decoded = value;
            }
            return Optional.of(value);
        }

        private static String decodeUtf8(byte[] bytes) {
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException exception) {
                throw new IllegalStateException("Public text resource is not valid UTF-8", exception);
            }
        }
    }

    private enum BinaryOnly implements DetachedContent {
        INSTANCE;

        @Override
        public Optional<String> text() {
            return Optional.empty();
        }
    }

    private record DigestResult(long size, String sha256) {}
}
