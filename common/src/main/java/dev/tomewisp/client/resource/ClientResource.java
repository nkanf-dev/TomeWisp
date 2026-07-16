package dev.tomewisp.client.resource;

public record ClientResource(
        String resourceId,
        String packId,
        int priority,
        boolean selected,
        String content) {
    public ClientResource {
        if (resourceId == null || !resourceId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Invalid client resource ID: " + resourceId);
        }
        if (packId == null || packId.isBlank()) {
            throw new IllegalArgumentException("Pack ID must not be blank");
        }
        if (content == null) {
            throw new IllegalArgumentException("Resource content must not be null");
        }
    }

    public String namespace() {
        return resourceId.substring(0, resourceId.indexOf(':'));
    }

    public String path() {
        return resourceId.substring(resourceId.indexOf(':') + 1);
    }

    public String provenance() {
        return "pack=" + packId + ",resource=" + resourceId + ",priority=" + priority;
    }
}
