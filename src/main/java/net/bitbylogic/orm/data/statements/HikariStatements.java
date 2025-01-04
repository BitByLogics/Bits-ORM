package net.bitbylogic.orm.data.statements;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import net.bitbylogic.orm.HikariAPI;
import net.bitbylogic.orm.annotation.Column;
import net.bitbylogic.orm.data.ColumnData;
import net.bitbylogic.orm.data.HikariObject;
import net.bitbylogic.orm.data.HikariTable;
import net.bitbylogic.orm.processor.FieldProcessor;
import net.bitbylogic.orm.util.TypeToken;
import net.bitbylogic.utils.HashMapUtil;
import net.bitbylogic.utils.ListUtil;
import net.bitbylogic.utils.reflection.ReflectionUtil;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

@RequiredArgsConstructor
@Getter
public abstract class HikariStatements<O extends HikariObject> {

    private final HikariAPI hikariAPI;
    private final String tableName;

    private final List<ColumnData> columnData = new ArrayList<>();

    public void loadColumnData(@NonNull Object object, List<String> parentObjectFields) {
        List<Field> fields = new ArrayList<>();

        fields.addAll(Arrays.asList(object.getClass().getFields()));
        fields.addAll(Arrays.asList(object.getClass().getDeclaredFields()));

        List<String> originalFields = new ArrayList<>(parentObjectFields);

        fields.forEach(field -> {
            if (!field.isAnnotationPresent(Column.class)) {
                return;
            }

            Column data = field.getAnnotation(Column.class);

            if (!data.foreignTable().isEmpty() &&
                    (!field.getType().isInstance(HikariObject.class) &&
                            !ReflectionUtil.isListOf(field, HikariObject.class) &&
                            !ReflectionUtil.isMapOf(field, HikariObject.class))) {
                System.out.println("(HikariObject): Skipped field " + field.getName() + ", foreign classes must contain HikariObject!");
                return;
            }

            if (data.subClass()) {
                try {
                    field.setAccessible(true);
                    parentObjectFields.add(field.getName());
                    loadColumnData(field.get(object), new ArrayList<>(parentObjectFields));
                    parentObjectFields.clear();
                    parentObjectFields.addAll(originalFields);
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
                return;
            }

            columnData.add(new ColumnData(field, object.getClass().getName(), data, parentObjectFields, null, null));
        });
    }

    public abstract String getTableCreateStatement();

    protected abstract String getStatementDataBlock(boolean includeMetadata, String... includedFields);

    public abstract String getDataSaveStatement(O object, String... includedFields);

    public abstract String getDataDeleteStatement(O object);

    protected abstract String getUpdateStatement(O object, String... includedFields);

    public abstract String getFormattedData(@NonNull ColumnData columnData);

    protected String getValuesDataBlock(O object, String... includedFields) {
        StringBuilder builder = new StringBuilder();
        List<String> data = new ArrayList<>();

        columnData.stream().filter(columnData -> columnData.getColumn().primaryKey()).findFirst().ifPresent(columnData -> {
            try {
                Object fieldObject = getFieldObject(object, columnData);

                Column statementData = columnData.getColumn();
                Field field = columnData.getField();
                field.setAccessible(true);
                Object fieldValue = field.get(fieldObject);

                FieldProcessor processor = hikariAPI.getFieldProcessor(TypeToken.asTypeToken(field.getGenericType()));

                if (statementData.foreignTable().isEmpty()) {
                    data.add(String.format("%s", fieldValue == null ? "NULL" : "'" + processor.parseToObject(fieldValue) + "'"));
                    return;
                }

                data.add(String.format("%s", fieldValue == null ? "NULL" : "'" + getForeignFieldIdData(object, field, columnData) + "'"));
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        });

        columnData.forEach(columnData -> {
            if (columnData.getColumn().primaryKey() || columnData.getColumn().autoIncrement()) {
                return;
            }

            if (includedFields.length != 0 && Arrays.stream(includedFields).noneMatch(field -> field.equalsIgnoreCase(columnData.getField().getName()))) {
                return;
            }

            try {
                Object fieldObject = getFieldObject(object, columnData);

                Column statementData = columnData.getColumn();
                Field field = columnData.getField();
                field.setAccessible(true);
                Object fieldValue = field.get(fieldObject);

                FieldProcessor processor = hikariAPI.getFieldProcessor(TypeToken.asTypeToken(field.getGenericType()));

                if (statementData.foreignTable().isEmpty()) {
                    data.add(String.format("%s", fieldValue == null ? "NULL" : "'" + processor.parseToObject(fieldValue) + "'"));
                    return;
                }

                data.add(String.format("%s", fieldValue == null ? "NULL" : "'" + getForeignFieldIdData(object, field, columnData) + "'"));
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        });

        builder.append(String.join(", ", data));
        return builder.toString();
    }

    public Object getFieldObject(Object object, ColumnData columnData) {
        if (columnData.getParentObjectFields().isEmpty()) {
            return object;
        }

        for (int i = 0; i < columnData.getParentObjectFields().size(); i++) {
            String parentFieldName = columnData.getParentObjectFields().get(i);

            try {
                Field parentField = object.getClass().getDeclaredField(parentFieldName);
                parentField.setAccessible(true);
                object = parentField.get(object);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        return object;
    }

    public Object getId(HikariObject object) {
        try {
            Field primaryKeyField = getPrimaryKeyData().getField();
            primaryKeyField.setAccessible(true);
            return primaryKeyField.get(object);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }

        return null;
    }

    protected Object getForeignFieldIdData(Object object, Field field, ColumnData columnData) {
        if (columnData.getColumn().foreignTable().isEmpty()) {
            return null;
        }

        field.setAccessible(true);

        try {
            HikariTable<?> foreignTable = columnData.getForeignTable();
            Object fieldValue = field.get(object);

            if (foreignTable == null) {
                System.out.println("(HikariAPI): Missing foreign table: " + columnData.getColumn().foreignTable());
                return null;
            }

            if (field.getType().isInstance(HikariObject.class)) {
                return foreignTable.getStatements().getId((HikariObject) fieldValue);
            }

            Type fieldType = field.getGenericType();

            if (fieldType instanceof ParameterizedType parameterizedType) {
                Class<?> fieldClass = (Class<?>) parameterizedType.getRawType();

                if (List.class.isAssignableFrom(fieldClass)) {
                    List<HikariObject> list = (List<HikariObject>) fieldValue;
                    List<Object> newList = new ArrayList<>();
                    list.forEach(hikariObject -> newList.add(foreignTable.getStatements().getId(hikariObject)));

                    return ListUtil.listToString(newList);
                }

                if (!Map.class.isAssignableFrom(fieldClass)) {
                    return fieldValue;
                }

                Map<Object, HikariObject> map = (Map<Object, HikariObject>) fieldValue;
                HashMap<Object, Object> newMap = new HashMap<>();
                map.forEach((key, value) -> newMap.put(key, foreignTable.getStatements().getId(value)));

                return HashMapUtil.mapToString(newMap);
            }
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }

        return null;
    }

    public ColumnData getPrimaryKeyData() {
        return columnData.stream().filter(columnData -> columnData.getColumn().primaryKey()).findFirst().orElse(null);
    }

}
