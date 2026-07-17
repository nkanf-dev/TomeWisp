package dev.tomewisp.context;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record InventorySnapshot(
        List<InventorySlotSnapshot> slots,
        int totalSlots,
        int selectedHotbarSlot,
        int mainHandSlot,
        ItemStackSnapshot offHand,
        boolean complete,
        EvidenceMetadata evidence) {
    public InventorySnapshot {
        slots = List.copyOf(slots);
        if (totalSlots < 0 || slots.size() > totalSlots) {
            throw new IllegalArgumentException("inventory slot count is invalid");
        }
        if (complete && slots.size() != totalSlots) {
            throw new IllegalArgumentException("complete inventory must contain every slot");
        }
        HashSet<Integer> seen = new HashSet<>();
        for (InventorySlotSnapshot slot : slots) {
            if (slot.slot() >= totalSlots || !seen.add(slot.slot())) {
                throw new IllegalArgumentException("inventory slots must be unique and in range");
            }
        }
        if (selectedHotbarSlot < -1 || selectedHotbarSlot > 8) {
            throw new IllegalArgumentException("selectedHotbarSlot must be -1 or a hotbar index");
        }
        if (mainHandSlot < -1 || mainHandSlot >= totalSlots) {
            throw new IllegalArgumentException("mainHandSlot must be -1 or an inventory slot");
        }
        Objects.requireNonNull(offHand, "offHand");
        Objects.requireNonNull(evidence, "evidence");
    }
}
