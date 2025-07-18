package net.bitbylogic.orm.data.statements;

import lombok.Getter;
import lombok.NonNull;
import net.bitbylogic.orm.BormAPI;
import net.bitbylogic.orm.annotation.Column;
import net.bitbylogic.orm.data.ColumnData;
import net.bitbylogic.orm.data.BormObject;
import net.bitbylogic.orm.processor.FieldProcessor;
import net.bitbylogic.orm.util.TypeToken;

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
    public String getDataDeleteStatement(O object) {
        if (getColumnData().stream().noneMatch(columnData -> columnData.getColumn().primaryKey())) {
            getBormAPI().getLogger().severe("(" + getTableName() + ") No primary key for object, failed to delete!");
            return null;
        }

        StringBuilder builder = new StringBuilder();

        getColumnData().stream().filter(columnData -> columnData.getColumn().primaryKey()).findFirst()
                .ifPresent(columnData -> {
                    builder.append(String.format("DELETE FROM %s WHERE %s=", getTableName(), columnData.getName()));

                    try {
                        Object fieldObject = getFieldObject(object, columnData);

                        Column statementData = columnData.getColumn();
                        Field field = columnData.getField();
                        field.setAccessible(true);
                        Object fieldValue = field.get(fieldObject);

                        FieldProcessor processor = getBormAPI().getFieldProcessor(TypeToken.asTypeToken(field.getGenericType()));

                        if (statementData.foreignTable().isEmpty()) {
                            builder.append(String.format("%s;", fieldValue == null ? "NULL" : "'" + ((String) processor.processTo(fieldValue)).replace("'", "''") + "'"));
                            return;
                        }

                        builder.append(String.format("%s;", fieldValue == null ? "NULL" : "'" + getForeignFieldIdData(object, field, columnData) + "'"));
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                });

        return builder.toString();
    }

    @Override
    public String getDataSaveStatement(O object, String... includedFields) {
        StringBuilder builder = new StringBuilder(String.format("INSERT INTO %s (%s) VALUES(%s) ON DUPLICATE KEY UPDATE ",
                getTableName(), getStatementDataBlock(false, includedFields), getValuesDataBlock(object, includedFields)));

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

        return builder.append(String.join(", ", entries)).append(";").toString();
    }

    @Override
    protected String getUpdateStatement(O object, String... includedFields) {
        StringBuilder builder = new StringBuilder(String.format("UPDATE %s SET ", getTableName()));

        List<String> entries = new ArrayList<>();

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
                field.setAccessible(true);
                Object fieldValue = field.get(fieldObject);

                FieldProcessor processor = getBormAPI().getFieldProcessor(TypeToken.asTypeToken(field.getGenericType()));

                if (statementData.foreignTable().isEmpty()) {
                    entries.add(String.format("key= %s", fieldValue == null ? null : "'" + ((String) processor.processTo(fieldValue)).replace("'", "''") + "'"));
                    return;
                }

                entries.add(String.format("key= %s", fieldValue == null ? null : "'" + getForeignFieldIdData(object, field, columnData) + "'"));
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        });

        getColumnData().stream().filter(columnData -> columnData.getColumn().primaryKey()).findFirst()
                .ifPresent(columnData -> {
                    try {
                        Object fieldObject = getFieldObject(object, columnData);

                        Column statementData = columnData.getColumn();
                        Field field = columnData.getField();
                        field.setAccessible(true);
                        Object fieldValue = field.get(fieldObject);

                        builder.append(String.join(", ", entries)).append(" WHERE ").append(columnData.getName()).append(" = ");

                        FieldProcessor processor = getBormAPI().getFieldProcessor(TypeToken.asTypeToken(field.getGenericType()));

                        if (statementData.foreignTable().isEmpty()) {
                            builder.append(String.format("%s;", fieldValue == null ? "NULL" : "'" + ((String) processor.processTo(fieldValue)).replace("'", "''") + "'"));
                            return;
                        }

                        builder.append(String.format("%s;", fieldValue == null ? "NULL" : "'" + getForeignFieldIdData(object, field, columnData) + "'"));
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                });

        return builder.toString();
    }

    @Override
    public String getFormattedData(@NonNull ColumnData columnData) {
        if (columnData.getForeignKeyData() != null) {
            String dataType = columnData.getForeignKeyData().getDataType();
            Class<?> fieldClass = columnData.getField().getType();

            if (fieldClass.isAssignableFrom(List.class) || fieldClass.isAssignableFrom(Map.class)) {
                dataType = "LONGTEXT";
            }

            return columnData.getName() + " " + dataType + " " + (columnData.getForeignKeyData().getColumn().allowNull() ? "" : "NOT NULL")
                    + (columnData.getForeignKeyData().getColumn().autoIncrement() ? " AUTO_INCREMENT" : "");
        }

        return columnData.getName() + " " + columnData.getDataType() + " " +
                (columnData.getColumn().allowNull() ? "" : "NOT NULL") + (columnData.getColumn().autoIncrement() ? " AUTO_INCREMENT" : "");
    }

}
