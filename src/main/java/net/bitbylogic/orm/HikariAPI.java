package net.bitbylogic.orm;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Getter;
import lombok.NonNull;
import net.bitbylogic.orm.data.ColumnData;
import net.bitbylogic.orm.data.HikariObject;
import net.bitbylogic.orm.data.HikariTable;
import net.bitbylogic.orm.processor.FieldProcessor;
import net.bitbylogic.orm.processor.impl.DefaultFieldProcessor;
import net.bitbylogic.orm.processor.impl.StringListProcessor;
import net.bitbylogic.orm.util.TypeToken;
import net.bitbylogic.utils.Pair;
import net.bitbylogic.utils.reflection.ReflectionUtil;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

@Getter
public class HikariAPI {

    private final static DefaultFieldProcessor DEFAULT_FIELD_PROCESSOR = new DefaultFieldProcessor();

    private final HikariDataSource dataSource;
    private final DatabaseType type;

    private final HashMap<TypeToken<?>, FieldProcessor<?>> fieldProcessors = new HashMap<>();
    private final HashMap<String, Pair<String, HikariTable<?>>> tables = new HashMap<>();
    private final HashMap<HikariTable<?>, List<String>> pendingTables = new HashMap<>();

    public HikariAPI(@NonNull String address, @NonNull String database,
                     @NonNull String port, @NonNull String username, @NonNull String password) {
        this.type = DatabaseType.MYSQL;

        HikariConfig config = new HikariConfig();
        config.setMaximumPoolSize(10);
        config.setConnectionTimeout(Duration.ofSeconds(30).toMillis());
        config.setDataSourceClassName("com.mysql.cj.jdbc.MysqlDataSource");
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("serverName", address);
        config.addDataSourceProperty("port", port);
        config.addDataSourceProperty("databaseName", database);
        config.addDataSourceProperty("user", username);
        config.addDataSourceProperty("password", password);

        dataSource = new HikariDataSource(config);

        registerFieldProcessor(new TypeToken<>() {}, new StringListProcessor());
    }

    public HikariAPI(@NonNull HikariConfig config) {
        this.type = config.getJdbcUrl().contains("sqlite") ? DatabaseType.SQLITE : DatabaseType.MYSQL;

        dataSource = new HikariDataSource(config);

        registerFieldProcessor(new TypeToken<>() {}, new StringListProcessor());
    }

    public HikariAPI(@NonNull File databaseFile) {
        this.type = DatabaseType.SQLITE;

        if (!databaseFile.exists()) {
            try {
                databaseFile.createNewFile();
            } catch (IOException e) {
                System.out.println("[HikariAPI]: Unable to find database file!");
                dataSource = null;
                return;
            }
        }

        HikariConfig config = new HikariConfig();
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(Duration.ofSeconds(30).toMillis());
        config.setJdbcUrl("jdbc:sqlite:" + databaseFile);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.setConnectionInitSql("PRAGMA foreign_keys = ON;");

        dataSource = new HikariDataSource(config);

        registerFieldProcessor(new TypeToken<>() {}, new StringListProcessor());
    }

    public <O extends HikariObject, T extends HikariTable<O>> void registerTable(Class<? extends T> tableClass, Consumer<T> consumer) {
        if (tables.containsKey(tableClass.getSimpleName())) {
            System.out.println("[HikariAPI]: Couldn't register table " + tableClass.getSimpleName() + ", it's already registered.");
            return;
        }

        try (ForkJoinPool pool = ForkJoinPool.commonPool()) {
            pool.execute(() -> {
                try {
                    T table = ReflectionUtil.findAndCallConstructor(tableClass, this);

                    if (table == null || table.getTable() == null) {
                        System.out.println("[HikariAPI]: Unable to create instance of table " + tableClass.getSimpleName() + "!");
                        return;
                    }

                    for (ColumnData columnData : table.getStatements().getColumnData()) {
                        if (columnData.getColumn().foreignTable().isEmpty()) {
                            continue;
                        }

                        String foreignTableName = columnData.getColumn().foreignTable();
                        HikariTable<?> foreignTable = getTable(foreignTableName);

                        if (foreignTable == null) {
                            List<String> tables = pendingTables.getOrDefault(table, new ArrayList<>());
                            tables.add(foreignTableName);
                            pendingTables.put(table, tables);
                            System.out.println("[HikariAPI]: Table " + table.getTable() + " requires " + foreignTableName + " and will be loaded when it's loaded!");
                            getTables().put(tableClass.getSimpleName(), new Pair<>(table.getTable(), table));
                            consumer.accept(table);
                            return;
                        }

                        columnData.setForeignKeyData(foreignTable.getStatements().getPrimaryKeyData());
                        columnData.setForeignTable(foreignTable);
                    }

                    getTables().put(tableClass.getSimpleName(), new Pair<>(table.getTable(), table));
                    loadTable(table);

                    consumer.accept(table);
                } catch (InvocationTargetException | InstantiationException | IllegalAccessException e) {
                    System.out.println("[HikariAPI]: Couldn't create instance of table " + tableClass.getSimpleName() + "!");
                    e.printStackTrace();
                }
            });
        }
    }

    private synchronized void loadTable(@NonNull HikariTable<?> table) {
        executeStatement(table.getStatements().getTableCreateStatement(), resultSet -> {
            if (!table.isLoadData()) {
                System.out.println("[HikariAPI] [" + table.getClass().getSimpleName() + "]: Finished retrieving data.");
                checkForeignTables(table);
                return;
            }

            table.loadData(() -> {
                checkForeignTables(table);
            });
        });
    }

    private synchronized void checkForeignTables(@NonNull HikariTable<?> table) {
        Iterator<Map.Entry<HikariTable<?>, List<String>>> iterator = pendingTables.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<HikariTable<?>, List<String>> entry = iterator.next();

            if (!entry.getValue().contains(table.getTable())) {
                continue;
            }

            List<String> newTables = new ArrayList<>(entry.getValue());
            newTables.remove(table.getTable());

            HikariTable<?> pendingTable = entry.getKey();

            if (!newTables.isEmpty()) {
                pendingTables.put(pendingTable, newTables);
                continue;
            }

            for (ColumnData columnData : pendingTable.getStatements().getColumnData()) {
                if (!columnData.getColumn().foreignTable().equalsIgnoreCase(table.getTable())) {
                    continue;
                }

                columnData.setForeignKeyData(table.getStatements().getPrimaryKeyData());
                columnData.setForeignTable(table);
            }

            loadTable(pendingTable);
            iterator.remove();

            System.out.println("[HikariAPI]: All foreign tables loaded for " + pendingTable.getTable() + ", it will now be loaded!");
        }
    }

    public HikariTable<?> getTable(@NonNull String tableName) {
        return tables.values().stream()
                .filter(stringHikariTablePair -> stringHikariTablePair.getKey().equalsIgnoreCase(tableName))
                .map(Pair::getValue).findFirst().orElse(null);
    }

    public void executeStatement(String query, Object... arguments) {
        executeStatement(query, null, arguments);
    }

    public synchronized void executeStatement(@NonNull String query, @Nullable Consumer<ResultSet> consumer, @Nullable Object... arguments) {
        CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                try (PreparedStatement statement = connection.prepareStatement(query, PreparedStatement.RETURN_GENERATED_KEYS)) {
                    if (arguments != null) {
                        int index = 1;
                        for (Object argument : arguments) {
                            statement.setObject(index++, argument);
                        }
                    }

                    statement.executeUpdate();
                    try (ResultSet result = statement.getGeneratedKeys()) {
                        if (consumer == null) {
                            return;
                        }

                        consumer.accept(result);
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }).handle((unused, e) -> {
            if (e == null) {
                return null;
            }

            System.out.println("[HikariAPI]: Issue executing statement: " + query);
            e.printStackTrace();
            return null;
        });
    }

    public synchronized void executeQuery(@NonNull String query, @NonNull Consumer<ResultSet> consumer, @Nullable Object... arguments) {
        CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                try (PreparedStatement statement = connection.prepareStatement(query)) {
                    if (arguments != null) {
                        int index = 1;

                        for (Object argument : arguments) {
                            statement.setObject(index++, argument);
                        }
                    }

                    try (ResultSet result = statement.executeQuery()) {
                        consumer.accept(result);
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }).handle((unused, e) -> {
            if (e == null) {
                return null;
            }

            System.out.println("[HikariAPI]: Issue executing statement: " + query);
            e.printStackTrace();
            return null;
        });
    }

    public void close() {
        if(dataSource == null || dataSource.isClosed()) {
            return;
        }

        dataSource.close();
    }

    public <T> void registerFieldProcessor(@NonNull TypeToken<T> type, @NonNull FieldProcessor<T> processor) {
        if(fieldProcessors.containsKey(type)) {
            return;
        }

        fieldProcessors.put(type, processor);
    }

    public FieldProcessor<?> getFieldProcessor(@NonNull TypeToken<?> type) {
        return fieldProcessors.getOrDefault(type, DEFAULT_FIELD_PROCESSOR);
    }

}
