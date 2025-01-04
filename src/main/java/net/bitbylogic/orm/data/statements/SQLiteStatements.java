package net.bitbylogic.orm.data.statements;

import lombok.NonNull;
import net.bitbylogic.orm.HikariAPI;
import net.bitbylogic.orm.data.ColumnData;
import net.bitbylogic.orm.data.HikariObject;

import java.util.List;
import java.util.Map;

public class SQLiteStatements<O extends HikariObject> extends SQLStatements<O> {

    public SQLiteStatements(@NonNull HikariAPI hikariAPI, @NonNull String table) {
        super(hikariAPI, table);
    }

    @Override
    public String getDataSaveStatement(O object, String... includedFields) {
        return String.format("INSERT OR REPLACE INTO %s (%s) VALUES(%s)",
                getTableName(), getStatementDataBlock(false, includedFields), getValuesDataBlock(object, includedFields)) + ";";
    }

    @Override
    public String getFormattedData(@NonNull ColumnData columnData) {
        if (columnData.getForeignKeyData() != null) {
            String dataType = columnData.getDataType();
            Class<?> fieldClass = columnData.getField().getType();

            if (fieldClass.isAssignableFrom(List.class) || fieldClass.isAssignableFrom(Map.class)) {
                dataType = "LONGTEXT";
            }

            return columnData.getName() + (columnData.getForeignKeyData().getColumn().autoIncrement() ? " INTEGER PRIMARY KEY" : " " + dataType)
                    + " " + (columnData.getForeignKeyData().getColumn().allowNull() ? "" : "NOT NULL");
        }

        return columnData.getName() + (columnData.getColumn().autoIncrement() ? " INTEGER PRIMARY KEY" : " " + columnData.getDataType())
                + " " + (columnData.getColumn().allowNull() ? "" : "NOT NULL");
    }
}
