package dev.tomewisp.tool.builtin;

import java.util.regex.Pattern;

final class BuiltinToolValidation {
    private static final Pattern IDENTIFIER =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    private BuiltinToolValidation() {}

    static boolean isIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }
}
