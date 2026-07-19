package dev.tomewisp.capability;

/** Typed settings route; it carries no callback, path, URL, or permission. */
public record CapabilityChildPage(String routeId) {
    public CapabilityChildPage {
        routeId = CapabilityPolicy.requireToolId(routeId);
    }
}
