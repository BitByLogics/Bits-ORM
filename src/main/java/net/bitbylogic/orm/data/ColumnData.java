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
    private volatile ColumnData foreignKeyData;

    @Setter
    private volatile BormTable<?> foreignTable;

    public String getName() {
        if (!column.name().isEmpty()) {
            return column.name();
        }

        return getFlattenedFieldName();
    }

    public String getDataType() {
        return column.dataType().isEmpty() ? DataTypeInferencer.inferDataType(field.getType()) : column.dataType();
    }

    /**
     * This field's name, prefixed with the full chain of subClass field names it was flattened
     * through (if any) so that e.g. two subClass fields of the same type (billingAddress/shippingAddress)
     * don't collide. Used both as the default SQL column name and to match this column's value back to
     * a constructor parameter, independently of any SQL-only {@code @Column(name=...)} override.
     */
    public String getFlattenedFieldName() {
        if (parentObjectFields.isEmpty()) {
            return field.getName();
        }

        return String.join("_", parentObjectFields) + "_" + field.getName();
    }

    public NamedParameter asNamedParameter(@Nullable Object value) {
        return new NamedParameter(getFlattenedFieldName(), field.getType(), value);
    }

}
