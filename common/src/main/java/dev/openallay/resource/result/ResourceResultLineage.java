package dev.openallay.resource.result;

import dev.openallay.resource.vfs.ResourcePath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable inputs and operation identity from which a result was derived. */
public record ResourceResultLineage(
        List<ResourcePath> sourcePaths,
        List<ResourcePath> priorResultPaths,
        String operationDigest) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public ResourceResultLineage {
        sourcePaths = canonicalPaths(sourcePaths, false, "sourcePaths");
        priorResultPaths = canonicalPaths(priorResultPaths, true, "priorResultPaths");
        Objects.requireNonNull(operationDigest, "operationDigest");
        if (!SHA_256.matcher(operationDigest).matches()) {
            throw new IllegalArgumentException("operationDigest must be a lowercase SHA-256 digest");
        }
    }

    public static String digestOperation(String operationId, String canonicalArguments) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId is required");
        }
        Objects.requireNonNull(canonicalArguments, "canonicalArguments");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(operationId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(canonicalArguments.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static List<ResourcePath> canonicalPaths(List<ResourcePath> paths, boolean requireResult, String name) {
        ArrayList<ResourcePath> copy = new ArrayList<>(Objects.requireNonNull(paths, name));
        if (copy.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException(name + " contains null");
        }
        Set<ResourcePath> unique = new HashSet<>(copy);
        if (unique.size() != copy.size()) {
            throw new IllegalArgumentException(name + " contains duplicate paths");
        }
        for (ResourcePath path : copy) {
            boolean isResult = path.mount().equals("result");
            if (requireResult && (!isResult || path.segments().size() != 2)) {
                throw new IllegalArgumentException("Prior result lineage must use /result/<id> paths");
            }
            if (!requireResult && isResult) {
                throw new IllegalArgumentException("Result inputs belong in priorResultPaths");
            }
        }
        copy.sort(Comparator.naturalOrder());
        return List.copyOf(copy);
    }
}
