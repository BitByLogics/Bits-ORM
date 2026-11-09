package net.bitbylogic.orm.data.statements;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import net.bitbylogic.orm.BormAPI;
import net.bitbylogic.orm.annotation.Column;
import net.bitbylogic.orm.data.BormObject;
import net.bitbylogic.orm.data.BormTable;
import net.bitbylogic.orm.data.ColumnData;
import net.bitbylogic.orm.processor.FieldProcessor;
import net.bitbylogic.orm.util.SqlIdentifier;
import net.bitbylogic.utils.HashMapUtil;
import net.bitbylogic.utils.ListUtil;
import net.bitbylogic.utils.Pair;
import net.bitbylogic.utils.reflection.ReflectionUtil;
import net.bitbylogic.utils.reflection.TypeToken;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.logging.Level;

@RequiredArgsConstructor
@Getter
public abstract class BormStatements<O extends BormObject> {

    private final BormAPI bormAPI;
    private final String tableName;

    @Getter(AccessLevel.NONE)
    private final List<ColumnData> columnData = new ArrayList<>();

    public void loadColumnData(@NonNull Object object, List<String> parentObjectFields) {
        Set<Field> fields = new LinkedHashSet<>();

        fields.addAll(Arrays.asList(object.getClass().getFields()));

        for (Class<?> current = object.getClass(); current != null && current != Object.class; current = current.getSuperclass()) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
        }

        List<String> originalFields = new ArrayList<>(parentObjectFields);

        fields.forEach(field -> {
            if (!field.isAnnotationPresent(Column.class)) {
                return;
            }

            Column data = field.getAnnotation(Column.class);

            if (!data.foreignTable().isEmpty() &&
                    (!BormObject.class.isAssignableFrom(field.getType()) &&
                            !isContainerOfBormObjects(field))) {
                getBormAPI().getLogger().warning("(" + object.getClass().getSimpleName() + "): Skipped field " + field.getName() + ", foreign classes must extend BormObject!");
                return;
            }

            if (data.subClass()) {
                try {
                    field.setAccessible(true);

                    Object nestedObject = field.get(object);

                    if (nestedObject == null) {
                        Constructor<?> nestedConstructor = ReflectionUtil.findConstructor(field.getType());

                        if (nestedConstructor == null) {
                            getBormAPI().getLogger().warning("(" + object.getClass().getSimpleName() + "): Unable to inspect subClass field " + field.getName() + ", missing empty constructor.");
                            return;
                        }

                        nestedObject = nestedConstructor.newInstance();
                    }

                    parentObjectFields.add(field.getName());
                    loadColumnData(nestedObject, new ArrayList<>(parentObjectFields));
                    parentObjectFields.clear();
                    parentObjectFields.addAll(originalFields);
                } catch (IllegalAccessException | InstantiationException | InvocationTargetException e) {
                    logError("Unable to inspect subClass field " + field.getName(), e);
                }
                return;
            }

            field.setAccessible(true);

            ColumnData newColumnData = new ColumnData(field, object.getClass().getName(), data, new ArrayList<>(parentObjectFields), null, null);

            if (!SqlIdentifier.isValid(newColumnData.getName())) {
                getBormAPI().getLogger().warning("(" + object.getClass().getSimpleName() + "): Skipped field " + field.getName() + ", \"" + newColumnData.getName() + "\" is not a valid column name.");
                return;
            }

            columnData.add(newColumnData);
        });
    }

    public List<ColumnData> getColumnData() {
        return Collections.unmodifiableList(columnData);
    }

    protected void logError(String message, Throwable throwable) {
        bormAPI.getLogger().log(Level.SEVERE, "(" + tableName + "): " + message, throwable);
    }

    private boolean isContainerOfBormObjects(Field field) {
        if (!(field.getGenericType() instanceof ParameterizedType parameterizedType)) {
            return false;
        }

        Type[] typeArguments = parameterizedType.getActualTypeArguments();
        Type elementType;

        if (List.class.isAssignableFrom(field.getType()) && typeArguments.length == 1) {
            elementType = typeArguments[0];
        } else if (Map.class.isAssignableFrom(field.getType()) && typeArguments.length == 2) {
            elementType = typeArguments[1];
        } else {
            return false;
        }

        return elementType instanceof Class<?> elementClass && BormObject.class.isAssignableFrom(elementClass);
    }

    public Optional<ColumnData> getColumnData(@NonNull String name) {
        return columnData.stream().filter(data -> data.getName().equalsIgnoreCase(name)).findFirst();
    }

    public abstract String getTableCreateStatement();

    public String getTableClearStatement() {
        return "DELETE FROM " + tableName + ";";
    }

    protected abstract String getStatementDataBlock(boolean includeMetadata, String... includedFields);

    public abstract Pair<String, List<Object>> getDataSaveStatement(O object, String... includedFields);

    public abstract Pair<String, List<Object>> getDataDeleteStatement(O object);

    protected abstract Pair<String, List<Object>> getUpdateStatement(O object, String... includedFields);

    public abstract String getFormattedData(@NonNull ColumnData columnData);

    /**
     * Builds the "?, ?, ..." placeholder block for an INSERT's VALUES(...) clause,
     * paired with the actual values to bind, in the same order as the placeholders.
     */
    protected Pair<String, List<Object>> getValuesDataBlock(O object, String... includedFields) {
        List<String> placeholders = new ArrayList<>();
        List<Object> values = new ArrayList<>();

        columnData.stream().filter(columnData -> columnData.getColumn().primaryKey()).findFirst().ifPresent(columnData -> {
            try {
                Object fieldObject = getFieldObject(object, columnData);

                Column statementData = columnData.getColumn();
                Field field = columnData.getField();
                Object fieldValue = field.get(fieldObject);

                placeholders.add("?");

                if (statementData.autoIncrement() && isUnassigned(fieldValue)) {
                    values.add(null);
                    return;
                }

                if (statementData.foreignTable().isEmpty()) {
                    FieldProcessor processor = bormAPI.getFieldProcessor(TypeToken.asTypeToken(field.getGenericType()));
                    values.add(fieldValue == null ? null : processor.processTo(fieldValue));
                    return;
                }

                values.add(fieldValue == null ? null : getForeignFieldIdData(object, field, columnData));
            } catch (IllegalAccessException e) {
                logError("Unable to read field value", e);
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
                Object fieldValue = field.get(fieldObject);

                placeholders.add("?");

                if (statementData.foreignTable().isEmpty()) {
                    FieldProcessor processor = bormAPI.getFieldProcessor(TypeToken.asTypeToken(field.getGenericType()));
                    values.add(fieldValue == null ? null : processor.processTo(fieldValue));
                    return;
                }

                values.add(fieldValue == null ? null : getForeignFieldIdData(object, field, columnData));
            } catch (IllegalAccessException e) {
                logError("Unable to read field value", e);
            }
        });

        return new Pair<>(String.join(", ", placeholders), values);
    }

    /**
     * Whether an auto-increment key still has its default value, meaning the database should
     * generate it on insert.
     */
    public boolean isUnassigned(Object idValue) {
        if (idValue == null) {
            return true;
        }

        return idValue instanceof Number number && number.longValue() == 0;
    }

    public Object getFieldObject(Object object, ColumnData columnData) {
        if (columnData.getParentObjectFields().isEmpty()) {
            return object;
        }

        for (int i = 0; i < columnData.getParentObjectFields().size(); i++) {
            String parentFieldName = columnData.getParentObjectFields().get(i);

            try {
                Field parentField = findDeclaredField(object.getClass(), parentFieldName);
                parentField.setAccessible(true);
                object = parentField.get(object);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        return object;
    }

    private Field findDeclaredField(Class<?> clazz, String name) throws NoSuchFieldException {
        for (Class<?> current = clazz; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }

        throw new NoSuchFieldException(name);
    }

    public Object getId(BormObject object) {
        try {
            return getPrimaryKeyData().getField().get(object);
        } catch (IllegalAccessException e) {
            logError("Unable to read field value", e);
        }

        return null;
    }

    protected Object getForeignFieldIdData(Object object, Field field, ColumnData columnData) {
        if (columnData.getColumn().foreignTable().isEmpty()) {
            return null;
        }

        try {
            BormTable<?> foreignTable = columnData.getForeignTable();
            Object fieldValue = field.get(object);

            if (foreignTable == null) {
                getBormAPI().getLogger().severe("(" + tableName + "): Invalid foreign table: " + columnData.getColumn().foreignTable());
                return null;
            }

            if (BormObject.class.isAssignableFrom(field.getType())) {
                return foreignTable.getStatements().getId((BormObject) fieldValue);
            }

            Type fieldType = field.getGenericType();

            if (fieldType instanceof ParameterizedType parameterizedType) {
                Class<?> fieldClass = (Class<?>) parameterizedType.getRawType();

                if (List.class.isAssignableFrom(fieldClass)) {
                    List<BormObject> list = (List<BormObject>) fieldValue;
                    List<Object> newList = new ArrayList<>();
                    list.forEach(bormObject -> newList.add(foreignTable.getStatements().getId(bormObject)));

                    return ListUtil.listToString(newList);
                }

                if (!Map.class.isAssignableFrom(fieldClass)) {
                    return fieldValue;
                }

                Map<Object, BormObject> map = (Map<Object, BormObject>) fieldValue;
                HashMap<Object, Object> newMap = new HashMap<>();
                map.forEach((key, value) -> newMap.put(key, foreignTable.getStatements().getId(value)));

                return HashMapUtil.mapToString(newMap);
            }
        } catch (IllegalAccessException e) {
            logError("Unable to read field value", e);
        }

        return null;
    }

    public ColumnData getPrimaryKeyData() {
        return columnData.stream().filter(columnData -> columnData.getColumn().primaryKey()).findFirst().orElse(null);
    }

}
