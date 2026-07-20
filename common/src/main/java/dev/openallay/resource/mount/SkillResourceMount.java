package dev.openallay.resource.mount;

import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourceLink;
import dev.openallay.resource.vfs.ResourceMount;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceSnapshot;
import dev.openallay.resource.vfs.ResourceValue;
import dev.openallay.skill.SkillCatalog;
import dev.openallay.skill.SkillDocument;
import dev.openallay.skill.SkillMetadata;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;

/** Read-only VFS projection of validated Agent Skill packages and their references. */
public final class SkillResourceMount implements ResourceMount {
    private final Supplier<? extends SkillCatalog> source;
    private final String gameVersion;
    private final String loader;
    private final Clock clock;
    private long generation;

    public SkillResourceMount(
            Supplier<? extends SkillCatalog> source, String gameVersion, String loader) {
        this(source, gameVersion, loader, Clock.systemUTC());
    }

    SkillResourceMount(
            Supplier<? extends SkillCatalog> source,
            String gameVersion,
            String loader,
            Clock clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.gameVersion = requireText(gameVersion, "gameVersion");
        this.loader = requireText(loader, "loader");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ResourcePath root() {
        return ResourcePath.of("skill");
    }

    @Override
    public synchronized ResourceSnapshot snapshot() {
        Instant capturedAt = clock.instant();
        SkillCatalog catalog = Objects.requireNonNull(source.get(), "skill catalog");
        EvidenceMetadata rootEvidence = evidence(capturedAt, "openallay:skill_catalog");
        ResourceTreeBuilder tree = new ResourceTreeBuilder(root(), rootEvidence);
        for (SkillMetadata metadata : catalog.metadata()) {
            SkillDocument document = catalog.find(metadata.name()).orElseThrow(
                    () -> new IllegalStateException("Skill disappeared during snapshot: " + metadata.name()));
            add(tree, document, capturedAt);
        }
        return new ResourceSnapshot(
                root(), "skill-" + ++generation, capturedAt, tree.build());
    }

    private void add(ResourceTreeBuilder tree, SkillDocument document, Instant capturedAt) {
        SkillMetadata metadata = document.metadata();
        ResourcePath skillPath = root().child(metadata.name());
        ResourcePath instructionsPath = skillPath.child("instructions");
        ArrayList<ResourceLink> links = new ArrayList<>();
        links.add(new ResourceLink("instructions", instructionsPath, "SKILL.md"));

        Map<String, ResourceValue> fields = new TreeMap<>();
        fields.put("name", new ResourceValue.Scalar(metadata.name()));
        fields.put("description", new ResourceValue.Scalar(metadata.description()));
        fields.put("origin", new ResourceValue.Scalar(metadata.origin().name().toLowerCase()));
        fields.put("provenance", new ResourceValue.Scalar(metadata.provenance()));
        fields.put("license", new ResourceValue.Scalar(metadata.license().orElse(null)));
        fields.put("compatibility", new ResourceValue.Scalar(metadata.compatibility().orElse(null)));
        TreeMap<String, ResourceValue> attributes = new TreeMap<>();
        metadata.attributes().forEach((key, value) -> attributes.put(key, new ResourceValue.Scalar(value)));
        fields.put("attributes", new ResourceValue.RecordValue(attributes));
        fields.put("required_mods", strings(metadata.requiredMods().stream().sorted().toList()));
        fields.put("allowed_tools", strings(metadata.allowedTools().stream().sorted().toList()));
        fields.put("instructions", new ResourceValue.ReferenceValue(instructionsPath, "instructions"));

        ArrayList<ResourceValue> referenceValues = new ArrayList<>();
        document.references().keySet().stream().sorted().forEach(reference -> {
            ResourcePath referencePath = skillPath.child("reference").child(reference);
            links.add(new ResourceLink("reference", referencePath, reference));
            referenceValues.add(new ResourceValue.ReferenceValue(referencePath, "reference"));
            tree.put(
                    referencePath,
                    ResourceKind.DOCUMENT,
                    document(reference, document.references().get(reference)),
                    List.of(),
                    evidence(capturedAt, "openallay:skill_reference"),
                    new ResourcePresentation(
                            ResourcePresentation.Kind.DOCUMENT,
                            Map.of("skill", metadata.name(), "reference", reference)));
        });
        fields.put("references", new ResourceValue.ListValue(referenceValues));

        tree.put(
                skillPath,
                ResourceKind.RECORD,
                new ResourceValue.RecordValue(fields),
                links,
                evidence(capturedAt, "openallay:skill_metadata"),
                ResourcePresentation.none());
        tree.put(
                instructionsPath,
                ResourceKind.DOCUMENT,
                document("SKILL.md", document.instructions()),
                List.of(),
                evidence(capturedAt, "openallay:skill_instructions"),
                new ResourcePresentation(
                        ResourcePresentation.Kind.DOCUMENT, Map.of("skill", metadata.name())));
    }

    private EvidenceMetadata evidence(Instant capturedAt, String sourceId) {
        return new EvidenceMetadata(
                DataAuthority.RESOURCE_ASSET,
                DataCompleteness.COMPLETE,
                capturedAt,
                sourceId,
                "openallay:validated_skill_package",
                gameVersion,
                loader,
                Map.of("openallay:authority", "procedural_only"));
    }

    private static ResourceValue.DocumentValue document(String title, String body) {
        return new ResourceValue.DocumentValue(
                title, List.of(new ResourceValue.DocumentSection("body", title, body)));
    }

    private static ResourceValue.ListValue strings(List<String> values) {
        return new ResourceValue.ListValue(values.stream()
                .map(value -> (ResourceValue) new ResourceValue.Scalar(value))
                .toList());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
