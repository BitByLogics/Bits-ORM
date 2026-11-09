package net.bitbylogic.orm.util;

import java.util.regex.Pattern;

public final class SqlIdentifier {

    private static final Pattern VALID_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    private SqlIdentifier() {
    }

    /**
     * Table and column names are inserted into SQL text (they can't be bound as parameters), so
     * only plain identifiers are accepted.
     */
    public static boolean isValid(String identifier) {
        return identifier != null && VALID_IDENTIFIER.matcher(identifier).matches();
    }

}
