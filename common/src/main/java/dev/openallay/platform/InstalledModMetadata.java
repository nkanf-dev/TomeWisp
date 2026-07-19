package dev.openallay.platform;

import java.util.List;
import java.util.Map;

/** Loader-neutral public metadata detached from the loader before Agent work starts. */
public record InstalledModMetadata(
        String id,
        String name,
        String version,
        String description,
        List<String> authors,
        List<String> licenses,
        Map<String, String> contacts,
        String environment,
        List<String> dependencies) {
    public InstalledModMetadata {
        id = require(id, "id");
        name = require(name, "name");
        version = require(version, "version");
        description = description == null ? "" : description;
        authors = List.copyOf(authors);
        licenses = List.copyOf(licenses);
        contacts = Map.copyOf(contacts);
        environment = require(environment, "environment");
        dependencies = List.copyOf(dependencies);
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
