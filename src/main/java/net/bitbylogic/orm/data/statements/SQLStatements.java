package net.bitbylogic.orm.data.statements;

import lombok.Getter;
import lombok.NonNull;
import net.bitbylogic.orm.annotation.HikariStatementData;
import net.bitbylogic.orm.data.HikariColumnData;
import net.bitbylogic.orm.data.HikariObject;
import net.bitbylogic.orm.processor.HikariFieldProcessor;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Getter
public class SQLStatements<O extends HikariObject> extends HikariStatements<O> {

    public SQLStatements(String table) {
        super(table);
    }

    @Override
    public String getTableCreateStatement() {
        StringBuilder builder = new StringBuilder(String.format("CREATE TABLE IF NOT EXISTS %s (%s", getTableName(), getStatementDataBlock(true)));

        getColumnData().stream().filter(columnData -> columnData.getStatementData().primaryKey()).findFirst()
                .ifPresent(columnData -> builder.append(String.format(", PRIMARY KEY(%s)", columnData.getColumnName())));

        return builder.append(");").toString();
    }

    protected String getStatementDataBlock(boolean includeMetadata, String... includedFields) {
        StringBuilder builder = new StringBuilder();

        List<String> keys = new ArrayList<>();
        List<String> values = new ArrayList<>();

        getColumnData().stream().filter(data -> data.getStatementData().primaryKey()).findFirst().ifPresent(columnData -> {
            keys.add(columnData.getColumnName());
            values.add(getFormattedData(columnData));
        });

        getColumnData().forEach(columnData -> {
            if (columnData.getStatementData().primaryKey() || columnData.getStatementData().autoIncrement()) {
                return;
            }

            if (includedFields.length != 0 && Arrays.stream(includedFields).noneMatch(field -> field.equalsIgnoreCase(columnData.getField().getName()))) {
                return;
            }

            keys.add(columnData.getColumnName());
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
        if (getColumnData().stream().noneMatch(columnData -> columnData.getStatementData().primaryKey())) {
            System.out.printf("[APIByLogic] [HikariAPI] (%s) No primary key, aborting.%n", getTableName());
            return null;
        }

        StringBuilder builder = new StringBuilder();

        getColumnData().stream().filter(columnData -> columnData.getStatementData().primaryKey()).findFirst()
                .ifPresent(columnData -> {
                    builder.append(String.format("DELETE FROM %s WHERE %s=", getTableName(), columnData.getColumnName()));

                    try {
                        Object fieldObject = getFieldObject(object, columnData);

                        HikariStatementData statementData = columnData.getStatementData();
                        Field field = columnData.getField();
                        field.setAccessible(true);
                        Object fieldValue = field.get(fieldObject);

                        HikariFieldProcessor processor = getCachedProcessors().get(field.getName());

                        if (processor == null) {
                            try {
                                processor = statementData.processor().getDeclaredConstructor().newInstance();
                                getCachedProcessors().put(field.getName(), processor);
                            } catch (InstantiationException | InvocationTargetException | NoSuchMethodException e) {
                                e.printStackTrace();
                                return;
                            }
                        }

                        if (statementData.foreignTable().isEmpty()) {
                            builder.append(String.format("%s;", fieldValue == null ? "NULL" : "'" + processor.parseToObject(fieldValue) + "'"));
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
            if (columnData.getStatementData().primaryKey() || columnData.getStatementData().autoIncrement()) {
                return;
            }

            if (includedFields.length != 0 && Arrays.stream(includedFields).noneMatch(field -> field.equalsIgnoreCase(columnData.getField().getName()))) {
                return;
            }

            if (!columnData.getStatementData().updateOnSave()) {
                return;
            }

            entries.add(columnData.getColumnName() + "=VALUES(" + columnData.getColumnName() + ")");
        });

        return builder.append(String.join(", ", entries)).append(";").toString();
    }

    @Override
    protected String getUpdateStatement(O object, String... includedFields) {
        StringBuilder builder = new StringBuilder(String.format("UPDATE %s SET ", getTableName()));

        List<String> entries = new ArrayList<>();

        getColumnData().forEach(columnData -> {
            if (columnData.getStatementData().primaryKey() || columnData.getStatementData().autoIncrement()) {
                return;
            }

            if (includedFields.length != 0 && Arrays.stream(includedFields).noneMatch(field -> field.equalsIgnoreCase(columnData.getField().getName()))) {
                return;
            }

            try {
                Object fieldObject = getFieldObject(object, columnData);

                HikariStatementData statementData = columnData.getStatementData();
                Field field = columnData.getField();
                field.setAccessible(true);
                Object fieldValue = field.get(fieldObject);

                HikariFieldProcessor processor = getCachedProcessors().get(field.getName());

                if (processor == null) {
                    try {
                        processor = statementData.processor().getDeclaredConstructor().newInstance();
                        getCachedProcessors().put(field.getName(), processor);
                    } catch (InstantiationException | InvocationTargetException | NoSuchMethodException e) {
                        e.printStackTrace();
                        return;
                    }
                }

                if (statementData.foreignTable().isEmpty()) {
                    entries.add(String.format("key= %s", fieldValue == null ? null : "'" + processor.parseToObject(fieldValue) + "'"));
                    return;
                }

                entries.add(String.format("key= %s", fieldValue == null ? null : "'" + getForeignFieldIdData(object, field, columnData) + "'"));
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        });

        getColumnData().stream().filter(columnData -> columnData.getStatementData().primaryKey()).findFirst()
                .ifPresent(columnData -> {
                    try {
                        Object fieldObject = getFieldObject(object, columnData);

                        HikariStatementData statementData = columnData.getStatementData();
                        Field field = columnData.getField();
                        field.setAccessible(true);
                        Object fieldValue = field.get(fieldObject);

                        builder.append(String.join(", ", entries)).append(" WHERE ").append(columnData.getColumnName()).append(" = ");

                        HikariFieldProcessor processor = getCachedProcessors().get(field.getName());

                        if (processor == null) {
                            try {
                                processor = statementData.processor().getDeclaredConstructor().newInstance();
                                getCachedProcessors().put(field.getName(), processor);
                            } catch (InstantiationException | InvocationTargetException | NoSuchMethodException e) {
                                e.printStackTrace();
                                return;
                            }
                        }

                        if (statementData.foreignTable().isEmpty()) {
                            builder.append(String.format("%s;", fieldValue == null ? "NULL" : "'" + processor.parseToObject(fieldValue) + "'"));
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
    public String getFormattedData(@NonNull HikariColumnData columnData) {
        if (columnData.getForeignKeyData() != null) {
            String dataType = columnData.getForeignKeyData().dataType();
            Class<?> fieldClass = columnData.getField().getType();

            if (fieldClass.isAssignableFrom(List.class) || fieldClass.isAssignableFrom(Map.class)) {
                dataType = "LONGTEXT";
            }

            return columnData.getColumnName() + " " + dataType + " " + (columnData.getForeignKeyData().allowNull() ? "" : "NOT NULL")
                    + (columnData.getForeignKeyData().autoIncrement() ? " AUTO_INCREMENT" : "");
        }

        return columnData.getColumnName() + " " + columnData.getStatementData().dataType() + " " +
                (columnData.getStatementData().allowNull() ? "" : "NOT NULL") + (columnData.getStatementData().autoIncrement() ? " AUTO_INCREMENT" : "");
    }

}
