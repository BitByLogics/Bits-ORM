package net.bitbylogic.orm;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.prometheus.PrometheusMetricsTrackerFactory;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import net.bitbylogic.orm.data.BormObject;
import net.bitbylogic.orm.data.BormTable;
import net.bitbylogic.orm.data.ColumnData;
import net.bitbylogic.orm.processor.FieldProcessor;
import net.bitbylogic.orm.processor.impl.DefaultFieldProcessor;
import net.bitbylogic.orm.processor.impl.StringListProcessor;
import net.bitbylogic.orm.redis.BormRedisHook;
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
import java.util.concurrent.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

@Getter
public class BormAPI {

    private final static DefaultFieldProcessor DEFAULT_FIELD_PROCESSOR = new DefaultFieldProcessor();

    private final Logger logger;
    private final HikariDataSource dataSource;
    private final ExecutorService dbExecutor;

    private final ConcurrentHashMap<TypeToken<?>, FieldProcessor<?>> fieldProcessors = new ConcurrentHashMap<>();

    private final HashMap<String, Pair<String, BormTable<?>>> tables = new HashMap<>();
    private final HashMap<BormTable<?>, List<String>> pendingTables = new HashMap<>();

    @Setter
    private DatabaseType type;

    @Setter
    private int batchSaveSize = 100000;

    @Setter
    private @Nullable BormRedisHook redisHook;

    public BormAPI(@NonNull String address, @NonNull String database,
                   @NonNull String port, @NonNull String username, @NonNull String password) {
        this.logger = Logger.getLogger("BORM");
        this.type = DatabaseType.MYSQL;

        this.dbExecutor = Executors.newWorkStealingPool();

        HikariConfig config = new HikariConfig();
        config.setMaximumPoolSize(10);
        config.setConnectionTimeout(Duration.ofSeconds(30).toMillis());
        config.setLeakDetectionThreshold(60000);
        config.setMetricsTrackerFactory(new PrometheusMetricsTrackerFactory());
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

        registerFieldProcessor(new TypeToken<>() {
        }, new StringListProcessor());
    }

    public BormAPI(@NonNull HikariConfig config) {
        this.logger = Logger.getLogger("BORM");
        this.type = config.getJdbcUrl().contains("sqlite") ? DatabaseType.SQLITE : DatabaseType.MYSQL;

        this.dbExecutor = this.type == DatabaseType.SQLITE
                ? Executors.newSingleThreadExecutor()
                : Executors.newWorkStealingPool();

        dataSource = new HikariDataSource(config);

        registerFieldProcessor(new TypeToken<>() {
        }, new StringListProcessor());
    }

    public BormAPI(@NonNull File databaseFile) {
        this.logger = Logger.getLogger("BORM");
        this.type = DatabaseType.SQLITE;

        this.dbExecutor = Executors.newSingleThreadExecutor();

        if (!databaseFile.exists()) {
            try {
                databaseFile.createNewFile();
            } catch (IOException e) {
                logger.severe("Unable to locate database file!");
                dataSource = null;
                return;
            }
        }

        HikariConfig config = new HikariConfig();
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(Duration.ofSeconds(60).toMillis());
        config.setMaxLifetime(Duration.ofMinutes(30).toMillis());
        config.setLeakDetectionThreshold(60000);
        config.setMetricsTrackerFactory(new PrometheusMetricsTrackerFactory());
        config.setJdbcUrl("jdbc:sqlite:" + databaseFile);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.setConnectionInitSql("PRAGMA journal_mode=WAL; " +
                "PRAGMA synchronous=NORMAL; " +
                "PRAGMA foreign_keys=ON; " +
                "PRAGMA busy_timeout=10000;"
        );

        dataSource = new HikariDataSource(config);

        registerFieldProcessor(new TypeToken<>() {}, new StringListProcessor());
    }

    public synchronized <O extends BormObject, T extends BormTable<O>> CompletableFuture<T> registerTable(Class<? extends T> tableClass) {
        if (tables.containsKey(tableClass.getSimpleName())) {
            logger.warning("Failed to register table " + tableClass.getSimpleName() + ", it's already registered.");
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                T table = ReflectionUtil.findAndCallConstructor(tableClass, this);

                if (table == null || table.getTable() == null) {
                    logger.severe("Unable to create instance of table " + tableClass.getSimpleName() + "!");
                    return null;
                }

                for (ColumnData columnData : table.getStatements().getColumnData()) {
                    if (columnData.getColumn().foreignTable().isEmpty()) continue;

                    String foreignTableName = columnData.getColumn().foreignTable();
                    BormTable<?> foreignTable = getTable(foreignTableName);

                    if (foreignTable == null) {
                        List<String> tables = pendingTables.getOrDefault(table, new ArrayList<>());
                        tables.add(foreignTableName);
                        pendingTables.put(table, tables);

                        logger.warning("Table " + table.getTable() + " requires " + foreignTableName + " and will be loaded later!");
                        getTables().put(tableClass.getSimpleName(), new Pair<>(table.getTable(), table));
                        return table;
                    }

                    columnData.setForeignKeyData(foreignTable.getStatements().getPrimaryKeyData());
                    columnData.setForeignTable(foreignTable);
                }

                getTables().put(tableClass.getSimpleName(), new Pair<>(table.getTable(), table));
                return table;
            } catch (InvocationTargetException | InstantiationException | IllegalAccessException e) {
                logger.log(Level.SEVERE, "Couldn't create instance of table " + tableClass.getSimpleName(), e);
                return null;
            }
        }, ForkJoinPool.commonPool()).thenCompose(finalTable -> finalTable != null ? loadTable(finalTable) : null);
    }

    private synchronized <T extends BormTable<?>> CompletableFuture<T> loadTable(@NonNull T table) {
        CompletableFuture<T> future = new CompletableFuture<>();

        executeStatement(table.getStatements().getTableCreateStatement(), resultSet -> {
            if (!table.isLoadData()) {
                logger.info("Finished loading table " + table.getTable() + ", data must be manually pulled.");
                checkForeignTables(table);
                future.complete(table);
                return;
            }

            table.loadData(() -> {
                checkForeignTables(table);
                future.complete(table);
            });
        });

        return future;
    }

    private synchronized void checkForeignTables(@NonNull BormTable<?> table) {
        Iterator<Map.Entry<BormTable<?>, List<String>>> iterator = pendingTables.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<BormTable<?>, List<String>> entry = iterator.next();

            if (!entry.getValue().contains(table.getTable())) {
                continue;
            }

            List<String> newTables = new ArrayList<>(entry.getValue());
            newTables.remove(table.getTable());

            BormTable<?> pendingTable = entry.getKey();

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

            logger.info("All foreign tables loaded for " + pendingTable.getTable() + ", it will now be loaded!");
        }
    }

    public synchronized BormTable<?> getTable(@NonNull String tableName) {
        return tables.values().stream()
                .filter(tablePair -> tablePair.getKey().equalsIgnoreCase(tableName))
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
        }, dbExecutor).handle((unused, e) -> {
            if (e == null) {
                return null;
            }

            logger.severe("Error executing statement: " + query);
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
        }, dbExecutor).handle((unused, e) -> {
            if (e == null) {
                return null;
            }

            logger.severe("Error executing query: " + query);
            e.printStackTrace();
            return null;
        });
    }

    public CompletableFuture<Void> executeBatch(@NonNull List<String> queries) {
        return executeBatch(queries, new ArrayList<>());
    }

    public synchronized CompletableFuture<Void> executeBatch(List<String> queries, List<Object[]> parametersList) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);

                for (int i = 0; i < queries.size(); i++) {
                    try (PreparedStatement statement = connection.prepareStatement(queries.get(i))) {

                        if (parametersList != null && !parametersList.isEmpty() && i < parametersList.size()) {
                            Object[] params = parametersList.get(i);

                            for (int j = 0; j < params.length; j++) {
                                statement.setObject(j + 1, params[j]);
                            }
                        }

                        statement.executeUpdate();
                    }
                }

                connection.commit();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, dbExecutor);
    }

    public void close() {
        if(dataSource == null || dataSource.isClosed()) {
            return;
        }

        dataSource.close();

        dbExecutor.shutdown();

        try {
            if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                dbExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            dbExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public <T> void registerFieldProcessor(@NonNull TypeToken<T> type, @NonNull FieldProcessor<T> processor) {
        fieldProcessors.putIfAbsent(type, processor);
    }

    public FieldProcessor<?> getFieldProcessor(@NonNull TypeToken<?> type) {
        for (Map.Entry<TypeToken<?>, FieldProcessor<?>> entry : fieldProcessors.entrySet()) {
            if (entry.getKey().getType().getTypeName().equalsIgnoreCase(type.getType().getTypeName())) {
                return entry.getValue();
            }
        }

        return DEFAULT_FIELD_PROCESSOR;
    }

}
