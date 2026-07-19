package dev.tomewisp.client.gui.settings;

import dev.tomewisp.capability.CapabilityChildPage;
import dev.tomewisp.capability.CapabilityKind;
import dev.tomewisp.capability.CapabilityPolicy;
import dev.tomewisp.capability.CapabilitySettingsEntry;
import dev.tomewisp.settings.capability.CapabilitySettingsView;
import dev.tomewisp.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Pure friendly-card projection and deny-policy draft edits for the native screen. */
public record CapabilitySettingsProjection(
        List<Card> cards,
        CapabilityPolicy policy,
        int retainedUnknownCount) {
    public CapabilitySettingsProjection {
        cards = List.copyOf(cards);
        Objects.requireNonNull(policy, "policy");
        if (retainedUnknownCount < 0) {
            throw new IllegalArgumentException("retainedUnknownCount must not be negative");
        }
    }

    public static CapabilitySettingsProjection from(
            CapabilitySettingsView view, CapabilityPolicy draft, boolean debugMode) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(draft, "draft");
        List<Card> cards = new ArrayList<>();
        for (CapabilitySettingsEntry entry : view.catalog().entries()) {
            boolean enabled = switch (entry.kind()) {
                case TOOL -> !draft.disabledTools().contains(entry.id());
                case SKILL -> !draft.disabledSkills().contains(entry.id());
                case KNOWLEDGE_SOURCE -> entry.enabled();
            };
            boolean toggleable = entry.childPage() == null
                    && (entry.kind() == CapabilityKind.TOOL
                            || entry.kind() == CapabilityKind.SKILL);
            cards.add(new Card(
                    entry.id(),
                    entry.kind(),
                    entry.titleKey(),
                    entry.descriptionKey(),
                    statusKey(entry.available(), enabled),
                    entry.available(),
                    enabled,
                    toggleable,
                    entry.childPage(),
                    debugMode ? entry.id() : null));
        }
        return new CapabilitySettingsProjection(
                cards,
                draft,
                view.unknownDisabledTools().size() + view.unknownDisabledSkills().size());
    }

    public ToolResult<CapabilityPolicy> toggle(String actionId) {
        Card card = cards.stream()
                .filter(value -> value.actionId().equals(actionId))
                .findFirst()
                .orElse(null);
        if (card == null || !card.toggleable()) {
            return new ToolResult.Failure<>(
                    "capability_not_toggleable", "This capability cannot be toggled here");
        }
        Set<String> tools = new TreeSet<>(policy.disabledTools());
        Set<String> skills = new TreeSet<>(policy.disabledSkills());
        Set<String> target = card.kind() == CapabilityKind.TOOL ? tools : skills;
        if (!target.remove(card.actionId())) {
            target.add(card.actionId());
        }
        return new ToolResult.Success<>(new CapabilityPolicy(
                CapabilityPolicy.SCHEMA_VERSION, tools, skills));
    }

    public List<Card> cards(CapabilityKind kind) {
        Objects.requireNonNull(kind, "kind");
        return cards.stream().filter(card -> card.kind() == kind).toList();
    }

    public CapabilityChildPage route(String actionId) {
        return cards.stream()
                .filter(value -> value.actionId().equals(actionId))
                .map(Card::childPage)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static String statusKey(boolean available, boolean enabled) {
        if (!available) {
            return "screen.tomewisp.settings.capability.unavailable";
        }
        return enabled
                ? "screen.tomewisp.settings.capability.enabled"
                : "screen.tomewisp.settings.capability.disabled";
    }

    public record Card(
            String actionId,
            CapabilityKind kind,
            String titleKey,
            String descriptionKey,
            String statusKey,
            boolean available,
            boolean enabled,
            boolean toggleable,
            CapabilityChildPage childPage,
            String debugId) {}
}
