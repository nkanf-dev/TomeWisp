package dev.tomewisp.client.resource;

import java.util.List;

public interface ClientResourceAccess {
    List<ClientResource> list(String pathPrefix);

    default List<ClientResource> selected(String pathPrefix) {
        return list(pathPrefix).stream().filter(ClientResource::selected).toList();
    }

    static String validatePrefix(String prefix) {
        if (prefix == null
                || prefix.isBlank()
                || prefix.startsWith("/")
                || prefix.contains("\\")
                || prefix.contains(":")
                || prefix.equals("..")
                || prefix.startsWith("../")
                || prefix.contains("/../")) {
            throw new IllegalArgumentException("Invalid asset path prefix: " + prefix);
        }
        String normalized = java.nio.file.Path.of(prefix).normalize().toString()
                .replace(java.io.File.separatorChar, '/');
        if (!normalized.equals(prefix) && !normalized.equals(prefix.replaceAll("/+$", ""))) {
            throw new IllegalArgumentException("Asset prefix must be normalized: " + prefix);
        }
        return normalized;
    }
}
