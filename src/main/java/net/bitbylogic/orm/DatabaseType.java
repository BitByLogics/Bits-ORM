package net.bitbylogic.orm;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import net.bitbylogic.orm.data.BormObject;
import net.bitbylogic.orm.data.statements.BormStatements;
import net.bitbylogic.orm.data.statements.SQLStatements;
import net.bitbylogic.orm.data.statements.SQLiteStatements;

@RequiredArgsConstructor
public enum DatabaseType {

    MYSQL,
    SQLITE;

    public <O extends BormObject> BormStatements<O> getStatements(@NonNull BormAPI bormAPI, @NonNull String table) {
        return switch (this) {
            case MYSQL -> new SQLStatements<>(bormAPI, table);
            case SQLITE -> new SQLiteStatements<>(bormAPI, table);
        };
    }

}
