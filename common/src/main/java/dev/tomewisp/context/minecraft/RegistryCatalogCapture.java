package dev.tomewisp.context.minecraft;

import dev.tomewisp.context.RegistryEntrySnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** Captures public built-in catalog data after the caller has proved Minecraft-thread ownership. */
public final class RegistryCatalogCapture {
    private RegistryCatalogCapture() {}

    public static List<RegistryEntrySnapshot> capture(
            String provenance, BooleanSupplier owningThread) {
        Objects.requireNonNull(owningThread, "owningThread");
        if (!owningThread.getAsBoolean()) {
            throw new IllegalStateException("Game content catalog must be captured on its Minecraft owning thread");
        }
        List<RegistryEntrySnapshot> entries = new ArrayList<>();
        Map<net.minecraft.world.item.Item, Set<String>> itemTags = tags(BuiltInRegistries.ITEM);
        BuiltInRegistries.ITEM.stream().forEach(item -> {
            Identifier id = Objects.requireNonNull(BuiltInRegistries.ITEM.getKey(item));
            Set<String> components = new TreeSet<>();
            item.components().keySet().forEach(type -> {
                Identifier componentId = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
                if (componentId != null) {
                    components.add(componentId.toString());
                }
            });
            entries.add(entry(
                    id,
                    "item",
                    item.getDefaultInstance().getHoverName().getString(),
                    provenance,
                    List.of(item.getDescriptionId()),
                    itemTags.getOrDefault(item, Set.of()),
                    components,
                    Map.of(
                            "description_id", item.getDescriptionId(),
                            "max_stack_size", Integer.toString(item.getDefaultMaxStackSize()))));
        });

        Map<net.minecraft.world.level.block.Block, Set<String>> blockTags = tags(BuiltInRegistries.BLOCK);
        BuiltInRegistries.BLOCK.stream().forEach(block -> {
            Identifier id = Objects.requireNonNull(BuiltInRegistries.BLOCK.getKey(block));
            String properties = block.getStateDefinition().getProperties().stream()
                    .map(property -> property.getName())
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(","));
            Map<String, String> metadata = new TreeMap<>();
            metadata.put("state_properties", properties.isBlank() ? "none" : properties);
            metadata.put("explosion_resistance", Float.toString(block.getExplosionResistance()));
            entries.add(entry(
                    id,
                    "block",
                    block.getName().getString(),
                    provenance,
                    List.of(block.getDescriptionId()),
                    blockTags.getOrDefault(block, Set.of()),
                    Set.of(),
                    metadata));
        });

        Map<net.minecraft.world.effect.MobEffect, Set<String>> effectTags = tags(BuiltInRegistries.MOB_EFFECT);
        BuiltInRegistries.MOB_EFFECT.stream().forEach(effect -> {
            Identifier id = Objects.requireNonNull(BuiltInRegistries.MOB_EFFECT.getKey(effect));
            entries.add(entry(
                    id,
                    "effect",
                    effect.getDisplayName().getString(),
                    provenance,
                    List.of(effect.getDescriptionId()),
                    effectTags.getOrDefault(effect, Set.of()),
                    Set.of(),
                    Map.of(
                            "category", effect.getCategory().name().toLowerCase(java.util.Locale.ROOT),
                            "beneficial", Boolean.toString(effect.isBeneficial()),
                            "instantaneous", Boolean.toString(effect.isInstantaneous()),
                            "color", Integer.toString(effect.getColor()))));
        });

        Map<net.minecraft.world.item.alchemy.Potion, Set<String>> potionTags = tags(BuiltInRegistries.POTION);
        BuiltInRegistries.POTION.stream().forEach(potion -> {
            Identifier id = Objects.requireNonNull(BuiltInRegistries.POTION.getKey(potion));
            String translationKey = "item.minecraft.potion.effect." + potion.name();
            String effects = potion.getEffects().stream()
                    .map(instance -> BuiltInRegistries.MOB_EFFECT.getKey(instance.getEffect().value()))
                    .filter(Objects::nonNull)
                    .map(Identifier::toString)
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(","));
            entries.add(entry(
                    id,
                    "potion",
                    net.minecraft.network.chat.Component.translatable(translationKey).getString(),
                    provenance,
                    List.of(potion.name(), translationKey),
                    potionTags.getOrDefault(potion, Set.of()),
                    Set.of(),
                    Map.of("effects", effects.isBlank() ? "none" : effects)));
        });

        Map<net.minecraft.world.entity.EntityType<?>, Set<String>> entityTags = tags(BuiltInRegistries.ENTITY_TYPE);
        BuiltInRegistries.ENTITY_TYPE.stream().forEach(entity -> {
            Identifier id = Objects.requireNonNull(BuiltInRegistries.ENTITY_TYPE.getKey(entity));
            entries.add(entry(
                    id,
                    "entity",
                    entity.getDescription().getString(),
                    provenance,
                    List.of(entity.getDescriptionId()),
                    entityTags.getOrDefault(entity, Set.of()),
                    Set.of(),
                    Map.of(
                            "category", entity.getCategory().getName(),
                            "summonable", Boolean.toString(entity.canSummon()),
                            "fire_immune", Boolean.toString(entity.fireImmune()))));
        });

        Map<net.minecraft.world.entity.ai.attributes.Attribute, Set<String>> attributeTags =
                tags(BuiltInRegistries.ATTRIBUTE);
        BuiltInRegistries.ATTRIBUTE.stream().forEach(attribute -> {
            Identifier id = Objects.requireNonNull(BuiltInRegistries.ATTRIBUTE.getKey(attribute));
            entries.add(entry(
                    id,
                    "attribute",
                    net.minecraft.network.chat.Component.translatable(attribute.getDescriptionId()).getString(),
                    provenance,
                    List.of(attribute.getDescriptionId()),
                    attributeTags.getOrDefault(attribute, Set.of()),
                    Set.of(),
                    Map.of(
                            "default_value", Double.toString(attribute.getDefaultValue()),
                            "client_syncable", Boolean.toString(attribute.isClientSyncable()))));
        });

        entries.sort(Comparator.comparing(RegistryEntrySnapshot::id)
                .thenComparing(RegistryEntrySnapshot::kind));
        return List.copyOf(entries);
    }

    private static RegistryEntrySnapshot entry(
            Identifier id,
            String kind,
            String displayName,
            String provenance,
            List<String> aliases,
            Set<String> tags,
            Set<String> components,
            Map<String, String> metadata) {
        return new RegistryEntrySnapshot(
                id.toString(),
                kind,
                displayName.isBlank() ? humanize(id.getPath()) : displayName,
                id.getNamespace(),
                provenance,
                aliases,
                tags,
                components,
                metadata);
    }

    private static <T> Map<T, Set<String>> tags(Registry<T> registry) {
        Map<T, Set<String>> result = new HashMap<>();
        registry.getTags().forEach(named -> {
            String tag = named.key().location().toString();
            named.stream().forEach(holder -> result
                    .computeIfAbsent(holder.value(), ignored -> new TreeSet<>())
                    .add(tag));
        });
        return result;
    }

    private static String humanize(String path) {
        String value = path.replace('_', ' ').replace('-', ' ').replace('/', ' ').strip();
        if (value.isEmpty()) {
            return path;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
