package dev.openallay.resource.vfs;

public interface ResourceMount {
    ResourcePath root();

    ResourceSnapshot snapshot();
}
