package dev.tomewisp.client.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Small safe formatter for the supported chat-like Markdown subset. */
final class GuideMarkup {
    private GuideMarkup() {}

    static List<Component> paragraphs(String value) {
        if (value == null || value.isEmpty()) return List.of(Component.empty());
        List<Component> output = new ArrayList<>();
        for (String raw : value.split("\\R", -1)) {
            String line = raw;
            MutableComponent component = Component.empty();
            if (line.startsWith("- ") || line.startsWith("* ")) {
                component.append(Component.literal("• ").withStyle(ChatFormatting.AQUA));
                line = line.substring(2);
            }
            appendInline(component, line);
            output.add(component);
        }
        return List.copyOf(output);
    }

    private static void appendInline(MutableComponent target, String text) {
        int index = 0;
        while (index < text.length()) {
            if (text.startsWith("**", index)) {
                int end = text.indexOf("**", index + 2);
                if (end >= 0) {
                    target.append(Component.literal(text.substring(index + 2, end)).withStyle(ChatFormatting.BOLD));
                    index = end + 2;
                    continue;
                }
            }
            char marker = text.charAt(index);
            if (marker == '`' || marker == '*') {
                int end = text.indexOf(marker, index + 1);
                if (end >= 0) {
                    var styled = Component.literal(text.substring(index + 1, end));
                    target.append(marker == '`'
                            ? styled.withStyle(ChatFormatting.GRAY)
                            : styled.withStyle(ChatFormatting.ITALIC));
                    index = end + 1;
                    continue;
                }
            }
            int next = index + 1;
            while (next < text.length()
                    && text.charAt(next) != '`'
                    && text.charAt(next) != '*') next++;
            target.append(Component.literal(text.substring(index, next)));
            index = next;
        }
    }
}
