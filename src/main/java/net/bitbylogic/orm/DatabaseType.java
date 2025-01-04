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

    MYSQL(SQLStatements.class),
    SQLITE(SQLiteStatements.class);

    private final Class<? extends HikariStatements> statementClass;

    public <O extends HikariObject> HikariStatements<O> getStatements(@NonNull String table) {
        try {
            return ReflectionUtil.findAndCallConstructor(statementClass, table);
        } catch (InvocationTargetException | InstantiationException | IllegalAccessException e) {
            e.printStackTrace();
        }

        return null;
    }

}
