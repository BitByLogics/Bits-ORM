package net.bitbylogic.orm;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import net.bitbylogic.orm.data.HikariObject;
import net.bitbylogic.orm.data.statements.HikariStatements;
import net.bitbylogic.orm.data.statements.SQLStatements;
import net.bitbylogic.orm.data.statements.SQLiteStatements;
import net.bitbylogic.utils.reflection.ReflectionUtil;

import java.lang.reflect.InvocationTargetException;

@RequiredArgsConstructor
public enum DatabaseType {

    MYSQL,
    SQLITE;

    public <O extends HikariObject> HikariStatements<O> getStatements(@NonNull HikariAPI hikariAPI, @NonNull String table) {
        return switch (this) {
            case MYSQL -> new SQLStatements<>(hikariAPI, table);
            case SQLITE -> new SQLiteStatements<>(hikariAPI, table);
        };
    }

}
