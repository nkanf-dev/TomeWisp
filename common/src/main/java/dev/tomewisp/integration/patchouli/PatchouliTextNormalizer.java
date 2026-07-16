package dev.tomewisp.integration.patchouli;

public final class PatchouliTextNormalizer {
    public String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String value = text.replace("$(br2)", "\n\n")
                .replace("$(br)", "\n")
                .replace("$(p)", "\n\n")
                .replace("$()", "");
        value = value.replaceAll("\\$\\([^)]*\\)", "");
        value = value.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n");
        return value.strip();
    }
}
