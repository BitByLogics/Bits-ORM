package net.bitbylogic.orm.data.statements;

import lombok.NonNull;
import net.bitbylogic.orm.BormAPI;
import net.bitbylogic.orm.data.ColumnData;
import net.bitbylogic.orm.data.BormObject;
import net.bitbylogic.utils.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SQLiteStatements<O extends BormObject> extends SQLStatements<O> {

    public SQLiteStatements(@NonNull BormAPI bormAPI, @NonNull String table) {
        super(bormAPI, table);
    }

    @Override
    public String getTableCreateStatement() {
        ColumnData primaryKeyData = getPrimaryKeyData();

        if (primaryKeyData == null || !primaryKeyData.getColumn().autoIncrement()) {
            return super.getTableCreateStatement();
        }

        return String.format("CREATE TABLE IF NOT EXISTS %s (%s);", getTableName(), getStatementDataBlock(true));
    }

    @Override
    public Pair<String, List<Object>> getDataSaveStatement(O object, String... includedFields) {
        Pair<String, List<Object>> valuesBlock = getValuesDataBlock(object, includedFields);

        StringBuilder builder = new StringBuilder(String.format("INSERT INTO %s (%s) VALUES(%s)",
                getTableName(), getStatementDataBlock(false, includedFields), valuesBlock.getKey()));

        ColumnData primaryKeyData = getPrimaryKeyData();

        if (primaryKeyData == null) {
            return new Pair<>(builder.append(";").toString(), valuesBlock.getValue());
        }

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

            entries.add(columnData.getName() + "=excluded." + columnData.getName());
        });

        builder.append(" ON CONFLICT(").append(primaryKeyData.getName()).append(") DO ");

        if (entries.isEmpty()) {
            return new Pair<>(builder.append("NOTHING;").toString(), valuesBlock.getValue());
        }

        return new Pair<>(builder.append("UPDATE SET ").append(String.join(", ", entries)).append(";").toString(), valuesBlock.getValue());
    }

    @Override
    public String getFormattedData(@NonNull ColumnData columnData) {
        if (columnData.getForeignKeyData() != null) {
            String dataType = columnData.getDataType();
            Class<?> fieldClass = columnData.getField().getType();

            if (List.class.isAssignableFrom(fieldClass) || Map.class.isAssignableFrom(fieldClass)) {
                dataType = "LONGTEXT";
            }

            return columnData.getName() + (columnData.getForeignKeyData().getColumn().autoIncrement() ? " INTEGER PRIMARY KEY" : " " + dataType)
                    + " " + (columnData.getForeignKeyData().getColumn().allowNull() ? "" : "NOT NULL");
        }

        return columnData.getName() + (columnData.getColumn().autoIncrement() ? " INTEGER PRIMARY KEY" : " " + columnData.getDataType())
                + " " + (columnData.getColumn().allowNull() ? "" : "NOT NULL");
    }
}
