package net.bitbylogic.orm.data.statements;

import lombok.NonNull;
import net.bitbylogic.orm.BormAPI;
import net.bitbylogic.orm.data.BormObject;
import net.bitbylogic.orm.data.ColumnData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SQLiteStatements<O extends BormObject> extends SQLStatements<O> {

    public SQLiteStatements(@NonNull BormAPI bormAPI, @NonNull String table) {
        super(bormAPI, table);
    }

    @Override
    public String getDataSaveStatement(O object, String... includedFields) {
        return getSaveStatement();
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

    @Override
    public void loadColumnData(@NonNull Object object, List<String> parentObjectFields) {
        super.loadColumnData(object, parentObjectFields);

        List<String> columns = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();

        for (ColumnData col : getColumnData()) {
            if (!col.getColumn().updateOnSave()) {
                continue;
            }

            columns.add(col.getName());
            placeholders.add("?");
        }

        setSaveStatement(String.format("INSERT OR REPLACE INTO %s (%s) VALUES (%s);",
                getTableName(),
                String.join(", ", columns),
                String.join(", ", placeholders)));
    }
}
