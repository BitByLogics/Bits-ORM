package net.bitbylogic.orm.data.statements;

import lombok.Getter;
import lombok.NonNull;
import net.bitbylogic.orm.BormAPI;
import net.bitbylogic.orm.annotation.Column;
import net.bitbylogic.orm.data.ColumnData;
import net.bitbylogic.orm.data.BormObject;
import net.bitbylogic.orm.processor.FieldProcessor;
import net.bitbylogic.utils.Pair;
import net.bitbylogic.utils.reflection.TypeToken;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Getter
public class SQLStatements<O extends BormObject> extends BormStatements<O> {

    public SQLStatements(@NonNull BormAPI bormAPI, @NonNull String table) {
        super(bormAPI, table);
    }

    @Override
    public String getTableCreateStatement() {
        StringBuilder builder = new StringBuilder(String.format("CREATE TABLE IF NOT EXISTS %s (%s", getTableName(), getStatementDataBlock(true)));

        getColumnData().stream().filter(columnData -> columnData.getColumn().primaryKey()).findFirst()
                .ifPresent(columnData -> builder.append(String.format(", PRIMARY KEY(%s)", columnData.getName())));

        return builder.append(");").toString();
    }

    protected String getStatementDataBlock(boolean includeMetadata, String... includedFields) {
        StringBuilder builder = new StringBuilder();

        List<String> keys = new ArrayList<>();
        List<String> values = new ArrayList<>();

        getColumnData().stream().filter(data -> data.getColumn().primaryKey()).findFirst().ifPresent(columnData -> {
            keys.add(columnData.getName());
            values.add(getFormattedData(columnData));
        });

        getColumnData().forEach(columnData -> {
            if (columnData.getColumn().primaryKey() || columnData.getColumn().autoIncrement()) {
                return;
            }

            if (includedFields.length != 0 && Arrays.stream(includedFields).noneMatch(field -> field.equalsIgnoreCase(columnData.getField().getName()))) {
                return;
            }

            keys.add(columnData.getName());
            values.add(getFormattedData(columnData));
        });

        if (includeMetadata) {
            builder.append(String.join(", ", values));
            return builder.toString();
        }

        builder.append(String.join(", ", keys));
        return builder.toString();
    }

    @Override
    public Pair<String, List<Object>> getDataDeleteStatement(O object) {
        if (getColumnData().stream().noneMatch(columnData -> columnData.getColumn().primaryKey())) {
            getBormAPI().getLogger().severe("(" + getTableName() + ") No primary key for object, failed to delete!");
            return null;
        }

        StringBuilder builder = new StringBuilder();
        List<Object> values = new ArrayList<>();

        getColumnData().stream().filter(columnData -> columnData.getColumn().primaryKey()).findFirst()
                .ifPresent(columnData -> {
                    builder.append(String.format("DELETE FROM %s WHERE %s=?;", getTableName(), columnData.getName()));

                    try {
                        Object fieldObject = getFieldObject(object, columnData);

                        Column statementData = columnData.getColumn();
                        Field field = columnData.getField();
                        Object fieldValue = field.get(fieldObject);

                        if (statementData.foreignTable().isEmpty()) {
                            FieldProcessor processor = getBormAPI().getFieldProcessor(TypeToken.asTypeToken(field.getGenericType()));
                            values.add(fieldValue == null ? null : processor.processTo(fieldValue));
                            return;
                        }

                        values.add(fieldValue == null ? null : getForeignFieldIdData(object, field, columnData));
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                });

        return new Pair<>(builder.toString(), values);
    }

    @Override
    public Pair<String, List<Object>> getDataSaveStatement(O object, String... includedFields) {
        Pair<String, List<Object>> valuesBlock = getValuesDataBlock(object, includedFields);

        StringBuilder builder = new StringBuilder(String.format("INSERT INTO %s (%s) VALUES(%s) ON DUPLICATE KEY UPDATE ",
                getTableName(), getStatementDataBlock(false, includedFields), valuesBlock.getKey()));

        List<String> entries = new ArrayList<>();

        getColumnData().forEach(columnData -> {
            if (columnData.getColumn().primaryKey() || columnData.getColumn().autoIncrement()) {
                return;
            }

            if (includedFields.length != 0 && Arrays.stream(includedFields).noneMatch(field -> field.equalsIgnoreCase(columnData.getField().getName()))) {
                return;
            }

            if (!columnData.getColumn().updateOnSave()) {
                return;
            }

            entries.add(columnData.getName() + "=VALUES(" + columnData.getName() + ")");
        });

        builder.append(String.join(", ", entries)).append(";");

        return new Pair<>(builder.toString(), valuesBlock.getValue());
    }

    @Override
    protected Pair<String, List<Object>> getUpdateStatement(O object, String... includedFields) {
        StringBuilder builder = new StringBuilder(String.format("UPDATE %s SET ", getTableName()));

        List<String> entries = new ArrayList<>();
        List<Object> values = new ArrayList<>();

        getColumnData().forEach(columnData -> {
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

                entries.add(columnData.getName() + "=?");

                if (statementData.foreignTable().isEmpty()) {
                    FieldProcessor processor = getBormAPI().getFieldProcessor(TypeToken.asTypeToken(field.getGenericType()));
                    values.add(fieldValue == null ? null : processor.processTo(fieldValue));
                    return;
                }

                values.add(fieldValue == null ? null : getForeignFieldIdData(object, field, columnData));
            } catch (IllegalAccessException e) {
                logError("Unable to read field value", e);
            }
        });

        getColumnData().stream().filter(columnData -> columnData.getColumn().primaryKey()).findFirst()
                .ifPresent(columnData -> {
                    try {
                        Object fieldObject = getFieldObject(object, columnData);

                        Column statementData = columnData.getColumn();
                        Field field = columnData.getField();
                        Object fieldValue = field.get(fieldObject);

                        builder.append(String.join(", ", entries)).append(" WHERE ").append(columnData.getName()).append(" = ?;");

                        if (statementData.foreignTable().isEmpty()) {
                            FieldProcessor processor = getBormAPI().getFieldProcessor(TypeToken.asTypeToken(field.getGenericType()));
                            values.add(fieldValue == null ? null : processor.processTo(fieldValue));
                            return;
                        }

                        values.add(fieldValue == null ? null : getForeignFieldIdData(object, field, columnData));
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                });

        return new Pair<>(builder.toString(), values);
    }

    /**
     * MySQL can't index a TEXT column without a key length, so a primary key whose type was only
     * inferred (a String) becomes VARCHAR(255). An explicit {@code dataType} is always kept as is.
     */
    private String resolveDataType(ColumnData columnData) {
        String dataType = columnData.getDataType();

        if (columnData.getColumn().primaryKey() && columnData.getColumn().dataType().isEmpty() && dataType.equals("TEXT")) {
            return "VARCHAR(255)";
        }

        return dataType;
    }

    @Override
    public String getFormattedData(@NonNull ColumnData columnData) {
        if (columnData.getForeignKeyData() != null) {
            String dataType = resolveDataType(columnData.getForeignKeyData());
            Class<?> fieldClass = columnData.getField().getType();

            if (List.class.isAssignableFrom(fieldClass) || Map.class.isAssignableFrom(fieldClass)) {
                dataType = "LONGTEXT";
            }

            return columnData.getName() + " " + dataType + " " + (columnData.getForeignKeyData().getColumn().allowNull() ? "" : "NOT NULL")
                    + (columnData.getForeignKeyData().getColumn().autoIncrement() ? " AUTO_INCREMENT" : "");
        }

        return columnData.getName() + " " + resolveDataType(columnData) + " " +
                (columnData.getColumn().allowNull() ? "" : "NOT NULL") + (columnData.getColumn().autoIncrement() ? " AUTO_INCREMENT" : "");
    }

}
