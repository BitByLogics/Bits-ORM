package net.bitbylogic.orm.data;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import net.bitbylogic.orm.annotation.Column;
import net.bitbylogic.orm.util.DataTypeInferencer;
import net.bitbylogic.utils.reflection.NamedParameter;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.List;

@AllArgsConstructor
@Getter
public class ColumnData {

    private final Field field;
    private final String parentClassName;
    private final Column column;
    private final List<String> parentObjectFields;

    @Setter
    private ColumnData foreignKeyData;

    @Setter
    private HikariTable<?> foreignTable;

    public String getName() {
        if (column.name().isEmpty()) {
            return column.subClass() ? parentClassName + "_" + field.getName() : field.getName();
        }

        return column.name();
    }

    public String getDataType() {
        return column.subClass() ? foreignKeyData.getDataType() : column.dataType().isEmpty()
                ? DataTypeInferencer.inferDataType(field.getType()) : column.dataType();
    }

    public NamedParameter asNamedParameter(@Nullable Object value) {
        return new NamedParameter(field.getName(), field.getType(), value);
    }

}
