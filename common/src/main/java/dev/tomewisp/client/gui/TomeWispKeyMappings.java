package dev.tomewisp.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** Shared identity and default for loader-registered client key mappings. */
public final class TomeWispKeyMappings {
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("tomewisp", "guide"));
    public static final KeyMapping OPEN_GUIDE = new KeyMapping(
            "key.tomewisp.open_guide", InputConstants.KEY_K, CATEGORY);

    private TomeWispKeyMappings() {}
}
