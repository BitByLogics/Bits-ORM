package net.bitbylogic.orm.data.statements;

import lombok.NonNull;
import net.bitbylogic.orm.data.HikariColumnData;
import net.bitbylogic.orm.data.HikariObject;

import java.util.List;
import java.util.Map;

public class SQLiteStatements<O extends HikariObject> extends SQLStatements<O> {

    public SQLiteStatements(String table) {
        super(table);
    }

    @Override
    public String getDataSaveStatement(O object, String... includedFields) {
        return String.format("INSERT OR REPLACE INTO %s (%s) VALUES(%s)",
                getTableName(), getStatementDataBlock(false, includedFields), getValuesDataBlock(object, includedFields)) + ";";
    }

    @Override
    public String getFormattedData(@NonNull HikariColumnData columnData) {
        if (columnData.getForeignKeyData() != null) {
            String dataType = columnData.getForeignKeyData().dataType();
            Class<?> fieldClass = columnData.getField().getType();

            if (fieldClass.isAssignableFrom(List.class) || fieldClass.isAssignableFrom(Map.class)) {
                dataType = "LONGTEXT";
            }

            return columnData.getColumnName() + (columnData.getForeignKeyData().autoIncrement() ? " INTEGER PRIMARY KEY" : " " + dataType)
                    + " " + (columnData.getForeignKeyData().allowNull() ? "" : "NOT NULL");
        }

        return columnData.getColumnName() + (columnData.getStatementData().autoIncrement() ? " INTEGER PRIMARY KEY" : " " + columnData.getStatementData().dataType())
                + " " + (columnData.getStatementData().allowNull() ? "" : "NOT NULL");
    }
}
