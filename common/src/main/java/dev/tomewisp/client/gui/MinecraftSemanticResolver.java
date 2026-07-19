package dev.tomewisp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Resolves registry IDs on the Minecraft client thread into detached render values. */
public final class MinecraftSemanticResolver {
    public record ItemPresentation(
            String itemId, String label, long count, ItemStack stack, boolean resolved) {
        public ItemPresentation {
            if (itemId == null || itemId.isBlank() || label == null || count < 0) {
                throw new IllegalArgumentException("invalid detached item presentation");
            }
            stack = stack == null ? ItemStack.EMPTY : stack.copy();
        }

        @Override public ItemStack stack() { return stack.copy(); }
    }

    public ItemPresentation item(String itemId, String suppliedLabel, long count) {
        String fallback = suppliedLabel == null || suppliedLabel.isBlank() ? itemId : suppliedLabel;
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            return new ItemPresentation(itemId, fallback, count, ItemStack.EMPTY, false);
        }
        Identifier id = Identifier.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return new ItemPresentation(itemId, fallback, count, ItemStack.EMPTY, false);
        }
        ItemStack stack = new ItemStack(
                BuiltInRegistries.ITEM.getValue(id),
                (int) Math.min(Integer.MAX_VALUE, Math.max(1, count)));
        String label = suppliedLabel == null || suppliedLabel.isBlank()
                ? stack.getHoverName().getString() : suppliedLabel;
        return new ItemPresentation(itemId, label, count, stack, true);
    }
}
