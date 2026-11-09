package net.bitbylogic.orm.query;

import lombok.NonNull;
import net.bitbylogic.orm.data.BormObject;
import net.bitbylogic.orm.data.BormTable;
import net.bitbylogic.orm.data.ColumnData;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A minimal single-table filter/sort/limit builder - Bits-ORM never joins across tables (foreign
 * keys are resolved as separate queries), so this deliberately doesn't try to either.
 * Results are not added to the table's cache; callers who want a specific result cached can pass
 * it to {@link BormTable#add(BormObject, boolean)} themselves.
 */
public class Query<O extends BormObject> {

    private final BormTable<O> table;
    private final List<Condition> conditions = new ArrayList<>();
    private String orderByColumn;
    private boolean ascending = true;
    private int limit = -1;

    public Query(@NonNull BormTable<O> table) {
        this.table = table;
    }

    public Query<O> where(@NonNull String fieldName, @NonNull QueryOperator operator, Object value) {
        conditions.add(new Condition(requireColumn(fieldName).getName(), operator, value));
        return this;
    }

    public Query<O> orderBy(@NonNull String fieldName, boolean ascending) {
        this.orderByColumn = requireColumn(fieldName).getName();
        this.ascending = ascending;
        return this;
    }

    public Query<O> limit(int limit) {
        this.limit = limit;
        return this;
    }

    public void execute(@NonNull Consumer<List<O>> callback) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(table.getTable());
        List<Object> args = new ArrayList<>();

        if (!conditions.isEmpty()) {
            List<String> clauses = new ArrayList<>();

            for (Condition condition : conditions) {
                clauses.add(condition.column() + " " + condition.operator().getSql() + " ?");
                args.add(condition.value());
            }

            sql.append(" WHERE ").append(String.join(" AND ", clauses));
        }

        if (orderByColumn != null) {
            sql.append(" ORDER BY ").append(orderByColumn).append(ascending ? " ASC" : " DESC");
        }

        if (limit >= 0) {
            sql.append(" LIMIT ").append(limit);
        }

        sql.append(";");

        table.getBormAPI().executeQueryAfterWrites(table.getTable(), sql.toString(), result -> table.loadObjects(result, callback), args.toArray());
    }

    private ColumnData requireColumn(String fieldName) {
        return table.getStatements().getColumnData(fieldName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown field \"" + fieldName + "\" on table " + table.getTable()));
    }

    private record Condition(String column, QueryOperator operator, Object value) {
    }

}
