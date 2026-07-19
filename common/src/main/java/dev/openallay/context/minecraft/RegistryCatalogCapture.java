package dev.openallay.context.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.openallay.context.RegistryEntrySnapshot;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
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
        return capture(provenance, owningThread, List.of());
    }

    public static List<RegistryEntrySnapshot> capture(
            String provenance,
            BooleanSupplier owningThread,
            List<? extends RegistryPropertyContributor> contributors) {
        Objects.requireNonNull(owningThread, "owningThread");
        List<? extends RegistryPropertyContributor> propertyContributors = List.copyOf(contributors);
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
                    properties("item", id, item, encodeComponents(item.components()), propertyContributors)));
        });

        Map<net.minecraft.world.level.block.Block, Set<String>> blockTags = tags(BuiltInRegistries.BLOCK);
        BuiltInRegistries.BLOCK.stream().forEach(block -> {
            Identifier id = Objects.requireNonNull(BuiltInRegistries.BLOCK.getKey(block));
            String properties = block.getStateDefinition().getProperties().stream()
                    .map(property -> property.getName())
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(","));
            JsonObject data = new JsonObject();
            data.addProperty("state_properties", properties.isBlank() ? "none" : properties);
            data.addProperty("explosion_resistance", block.getExplosionResistance());
            entries.add(entry(
                    id,
                    "block",
                    block.getName().getString(),
                    provenance,
                    List.of(block.getDescriptionId()),
                    blockTags.getOrDefault(block, Set.of()),
                    Set.of(),
                    properties("block", id, block, Map.of("minecraft:block", data), propertyContributors)));
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
                    properties("effect", id, effect, Map.of("minecraft:mob_effect", object(
                            "category", effect.getCategory().name().toLowerCase(java.util.Locale.ROOT),
                            "beneficial", effect.isBeneficial(),
                            "instantaneous", effect.isInstantaneous(),
                            "color", effect.getColor())), propertyContributors)));
        });

        Map<net.minecraft.world.item.alchemy.Potion, Set<String>> potionTags = tags(BuiltInRegistries.POTION);
        BuiltInRegistries.POTION.stream().forEach(potion -> {
            Identifier id = Objects.requireNonNull(BuiltInRegistries.POTION.getKey(potion));
            String translationKey = "item.minecraft.potion.effect." + potion.name();
            JsonArray effects = new JsonArray();
            potion.getEffects().forEach(instance -> {
                Identifier effectId = BuiltInRegistries.MOB_EFFECT.getKey(instance.getEffect().value());
                if (effectId == null) return;
                effects.add(object(
                        "id", effectId.toString(),
                        "duration", instance.getDuration(),
                        "amplifier", instance.getAmplifier(),
                        "ambient", instance.isAmbient(),
                        "visible", instance.isVisible(),
                        "show_icon", instance.showIcon()));
            });
            JsonObject potionData = new JsonObject();
            potionData.addProperty("name", potion.name());
            potionData.add("effects", effects);
            entries.add(entry(
                    id,
                    "potion",
                    net.minecraft.network.chat.Component.translatable(translationKey).getString(),
                    provenance,
                    List.of(potion.name(), translationKey),
                    potionTags.getOrDefault(potion, Set.of()),
                    Set.of(),
                    properties("potion", id, potion, Map.of("minecraft:potion", potionData), propertyContributors)));
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
                    properties("entity", id, entity, Map.of("minecraft:entity_type", object(
                            "category", entity.getCategory().getName(),
                            "summonable", entity.canSummon(),
                            "fire_immune", entity.fireImmune())), propertyContributors)));
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
                    properties("attribute", id, attribute, Map.of("minecraft:attribute", object(
                            "default_value", attribute.getDefaultValue(),
                            "client_syncable", attribute.isClientSyncable())), propertyContributors)));
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
            Map<String, JsonElement> properties) {
        return new RegistryEntrySnapshot(
                id.toString(),
                kind,
                displayName.isBlank() ? humanize(id.getPath()) : displayName,
                id.getNamespace(),
                provenance,
                aliases,
                tags,
                components,
                properties);
    }

    private static Map<String, JsonElement> encodeComponents(
            net.minecraft.core.component.DataComponentMap components) {
        Map<String, JsonElement> encoded = new TreeMap<>();
        var ops = RegistryOps.create(
                JsonOps.INSTANCE,
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        components.forEach(component -> {
            Identifier id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type());
            if (id == null) return;
            component.encodeValue(ops).result().ifPresent(value -> encoded.put(id.toString(), value));
        });
        return Map.copyOf(encoded);
    }

    private static Map<String, JsonElement> properties(
            String kind,
            Identifier resourceId,
            Object registryValue,
            Map<String, JsonElement> builtIn,
            List<? extends RegistryPropertyContributor> contributors) {
        Map<String, JsonElement> result = new TreeMap<>();
        builtIn.forEach((key, value) -> result.put(key, value.deepCopy()));
        for (RegistryPropertyContributor contributor : contributors) {
            Objects.requireNonNull(contributor, "registry property contributor");
            Map<String, JsonElement> contributed;
            try {
                contributed = contributor.capture(kind, resourceId, registryValue);
            } catch (RuntimeException unavailable) {
                // One optional Extension must not make the built-in catalog unavailable.
                continue;
            }
            if (contributed == null) continue;
            contributed.forEach((key, value) -> {
                if (key != null && value != null) result.putIfAbsent(key, value.deepCopy());
            });
        }
        return Map.copyOf(result);
    }

    private static JsonObject object(Object... entries) {
        JsonObject result = new JsonObject();
        for (int index = 0; index < entries.length; index += 2) {
            String key = (String) entries[index];
            Object value = entries[index + 1];
            if (value instanceof Boolean bool) result.addProperty(key, bool);
            else if (value instanceof Number number) result.addProperty(key, number);
            else result.addProperty(key, String.valueOf(value));
        }
        return result;
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
