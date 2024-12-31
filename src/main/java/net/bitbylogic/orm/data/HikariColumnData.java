package net.bitbylogic.orm.data;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import net.bitbylogic.orm.annotation.HikariStatementData;
import net.bitbylogic.utils.reflection.NamedParameter;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.List;

@AllArgsConstructor
@Getter
public class HikariColumnData {

    private final Field field;
    private final String parentClassName;
    private final HikariStatementData statementData;
    private final List<String> parentObjectFields;

    @Setter
    private HikariStatementData foreignKeyData;
    @Setter
    private HikariTable<?> foreignTable;

    public String getColumnName() {
        if (statementData.columnName().isEmpty()) {
            return statementData.subClass() ? parentClassName + "_" + field.getName() : field.getName();
        }

        return statementData.columnName();
    }

    public NamedParameter asNamedParameter(@Nullable Object value) {
        return new NamedParameter(field.getName(), field.getType(), value);
    }

}
