package net.bitbylogic.orm.data;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import net.bitbylogic.orm.annotation.Column;
import net.bitbylogic.orm.util.DataTypeInferencer;
import net.bitbylogic.utils.reflection.NamedParameter;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.List;

@Getter
public class ColumnData {

    private final Field field;
    private final MethodHandle getter;
    private final String parentClassName;
    private final Column column;
    private final List<String> parentObjectFields;

    @Setter
    private ColumnData foreignKeyData;

    @Setter
    private BormTable<?> foreignTable;

    public ColumnData(Field field, String parentClassName, Column column, List<String> parentObjectFields, ColumnData foreignKeyData, BormTable<?> foreignTable) {
        this.field = field;
        this.parentClassName = parentClassName;
        this.column = column;
        this.parentObjectFields = parentObjectFields;
        this.foreignKeyData = foreignKeyData;
        this.foreignTable = foreignTable;

        field.setAccessible(true);

        try {
            getter = MethodHandles.lookup().unreflectGetter(field);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

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
