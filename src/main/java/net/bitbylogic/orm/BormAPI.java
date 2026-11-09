package net.bitbylogic.orm;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.AccessLevel;
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
import net.bitbylogic.utils.Pair;
import net.bitbylogic.utils.reflection.ReflectionUtil;
import net.bitbylogic.utils.reflection.TypeToken;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

@Getter
public class BormAPI {

    private final static DefaultFieldProcessor DEFAULT_FIELD_PROCESSOR = new DefaultFieldProcessor();

    private final Logger logger;
    private final HikariDataSource dataSource;
    private final ExecutorService dbExecutor;

    @Getter(AccessLevel.NONE)
    private final ConcurrentHashMap<String, FieldProcessor<?>> fieldProcessors = new ConcurrentHashMap<>();

    @Getter(AccessLevel.NONE)
    private final ConcurrentHashMap<String, Pair<String, BormTable<?>>> tables = new ConcurrentHashMap<>();

    @Getter(AccessLevel.NONE)
    private final HashMap<BormTable<?>, Pair<List<String>, Consumer<BormTable<?>>>> pendingTables = new HashMap<>();

    @Getter(AccessLevel.NONE)
    private final ConcurrentHashMap<String, CompletableFuture<Void>> orderedTails = new ConcurrentHashMap<>();

    @Setter
    private DatabaseType type;

    @Setter
    private @Nullable BormRedisHook redisHook;

    public BormAPI(@NonNull String address, @NonNull String database,
                   @NonNull String port, @NonNull String username, @NonNull String password) {
        this.logger = Logger.getLogger("BORM");
        this.type = DatabaseType.MYSQL;

        HikariConfig config = new HikariConfig();
        config.setMaximumPoolSize(10);
        config.setConnectionTimeout(Duration.ofSeconds(30).toMillis());
        config.setLeakDetectionThreshold(60000);
        config.setDataSourceClassName("com.mysql.cj.jdbc.MysqlDataSource");
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");
        config.addDataSourceProperty("serverName", address);
        config.addDataSourceProperty("port", port);
        config.addDataSourceProperty("databaseName", database);
        config.addDataSourceProperty("user", username);
        config.addDataSourceProperty("password", password);

        this.dataSource = new HikariDataSource(config);
        this.dbExecutor = Executors.newWorkStealingPool();

        registerDefaultFieldProcessors();
    }

    public BormAPI(@NonNull HikariConfig config) {
        this(config, null);
    }

    BormAPI(@NonNull HikariConfig config, @Nullable ExecutorService executor) {
        this.logger = Logger.getLogger("BORM");

        String jdbcUrl = config.getJdbcUrl();
        this.type = jdbcUrl != null && jdbcUrl.contains("sqlite") ? DatabaseType.SQLITE : DatabaseType.MYSQL;

        this.dataSource = new HikariDataSource(config);

        if (executor != null) {
            this.dbExecutor = executor;
        } else {
            this.dbExecutor = this.type == DatabaseType.SQLITE ? createSingleThreadExecutor() : Executors.newWorkStealingPool();
        }

        registerDefaultFieldProcessors();
    }

    public BormAPI(@NonNull File databaseFile) {
        this.logger = Logger.getLogger("BORM");
        this.type = DatabaseType.SQLITE;

        File parentDirectory = databaseFile.getAbsoluteFile().getParentFile();

        if (parentDirectory != null && !parentDirectory.isDirectory() && !parentDirectory.mkdirs()) {
            throw new IllegalStateException("Unable to create directory " + parentDirectory);
        }

        try {
            databaseFile.createNewFile();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create database file " + databaseFile, e);
        }

        HikariConfig config = new HikariConfig();
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(Duration.ofSeconds(60).toMillis());
        config.setMaxLifetime(Duration.ofMinutes(30).toMillis());
        config.setLeakDetectionThreshold(60000);
        config.setJdbcUrl("jdbc:sqlite:" + databaseFile);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.setConnectionInitSql("PRAGMA journal_mode=WAL; " +
                "PRAGMA synchronous=NORMAL; " +
                "PRAGMA foreign_keys=ON; " +
                "PRAGMA busy_timeout=10000;"
        );

        this.dataSource = new HikariDataSource(config);
        this.dbExecutor = createSingleThreadExecutor();

        registerDefaultFieldProcessors();
    }

    private static ExecutorService createSingleThreadExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "borm-db");
            thread.setDaemon(true);
            return thread;
        });
    }

    private void registerDefaultFieldProcessors() {
        registerFieldProcessor(new TypeToken<>() {
        }, new StringListProcessor());
    }

    @SuppressWarnings("unchecked")
    public <O extends BormObject, T extends BormTable<O>> void registerTable(Class<? extends T> tableClass, Consumer<T> consumer) {
        String registrationName = tableClass.getSimpleName();

        if (tables.containsKey(registrationName)) {
            logger.warning("Failed to register table " + registrationName + ", it's already registered.");
            return;
        }

        ForkJoinPool.commonPool().execute(() -> {
            try {
                T table = ReflectionUtil.findAndCallConstructor(tableClass, this);

                if (table == null || table.getTable() == null) {
                    logger.severe("Unable to create instance of table " + registrationName + "!");
                    return;
                }

                if (table.getObjectConstructor() == null) {
                    logger.severe("Table " + registrationName + " was not registered, its object class has no usable constructor.");
                    return;
                }

                synchronized (this) {
                    if (tables.containsKey(registrationName)) {
                        logger.warning("Failed to register table " + registrationName + ", it's already registered.");
                        return;
                    }

                    List<String> missingTables = new ArrayList<>();

                    for (ColumnData columnData : table.getStatements().getColumnData()) {
                        String foreignTableName = columnData.getColumn().foreignTable();

                        if (foreignTableName.isEmpty()) {
                            continue;
                        }

                        BormTable<?> foreignTable = getTable(foreignTableName);

                        if (foreignTable == null) {
                            if (!missingTables.contains(foreignTableName)) {
                                missingTables.add(foreignTableName);
                            }
                            continue;
                        }

                        columnData.setForeignKeyData(foreignTable.getStatements().getPrimaryKeyData());
                        columnData.setForeignTable(foreignTable);
                    }

                    tables.put(registrationName, new Pair<>(table.getTable(), table));

                    if (!missingTables.isEmpty()) {
                        pendingTables.put(table, new Pair<>(missingTables, (Consumer<BormTable<?>>) consumer));
                        logger.warning("Table " + table.getTable() + " requires " + String.join(", ", missingTables) + " and will be loaded when they're loaded!");
                        return;
                    }
                }

                loadTable(table, () -> consumer.accept(table));
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Couldn't create instance of table " + registrationName, e);
            }
        });
    }

    private void loadTable(@NonNull BormTable<?> table, @Nullable Runnable onLoaded) {
        executeStatement(table.getStatements().getTableCreateStatement(), resultSet -> {
            if (!table.isLoadData()) {
                logger.info("Finished loading table " + table.getTable() + ", data must be manually pulled.");
                checkForeignTables(table);

                if (onLoaded != null) {
                    onLoaded.run();
                }
                return;
            }

            table.loadData(() -> {
                checkForeignTables(table);

                if (onLoaded != null) {
                    onLoaded.run();
                }
            });
        });
    }

    private synchronized void checkForeignTables(@NonNull BormTable<?> loadedTable) {
        Iterator<Map.Entry<BormTable<?>, Pair<List<String>, Consumer<BormTable<?>>>>> iterator = pendingTables.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<BormTable<?>, Pair<List<String>, Consumer<BormTable<?>>>> entry = iterator.next();
            List<String> requiredTables = entry.getValue().getKey();

            if (requiredTables.stream().noneMatch(name -> name.equalsIgnoreCase(loadedTable.getTable()))) {
                continue;
            }

            BormTable<?> pendingTable = entry.getKey();
            Consumer<BormTable<?>> pendingConsumer = entry.getValue().getValue();

            for (ColumnData columnData : pendingTable.getStatements().getColumnData()) {
                if (!columnData.getColumn().foreignTable().equalsIgnoreCase(loadedTable.getTable())) {
                    continue;
                }

                columnData.setForeignKeyData(loadedTable.getStatements().getPrimaryKeyData());
                columnData.setForeignTable(loadedTable);
            }

            List<String> remainingTables = new ArrayList<>(requiredTables);
            remainingTables.removeIf(name -> name.equalsIgnoreCase(loadedTable.getTable()));

            if (!remainingTables.isEmpty()) {
                entry.setValue(new Pair<>(remainingTables, pendingConsumer));
                continue;
            }

            iterator.remove();

            logger.info("All foreign tables loaded for " + pendingTable.getTable() + ", it will now be loaded!");
            loadTable(pendingTable, () -> pendingConsumer.accept(pendingTable));
        }
    }

    public Map<String, Pair<String, BormTable<?>>> getTables() {
        return Collections.unmodifiableMap(tables);
    }

    public BormTable<?> getTable(@NonNull String tableName) {
        for (Pair<String, BormTable<?>> tablePair : tables.values()) {
            if (tablePair.getKey().equalsIgnoreCase(tableName)) {
                return tablePair.getValue();
            }
        }

        return null;
    }

    public CompletableFuture<Void> executeStatement(String query, Object... arguments) {
        return executeStatement(query, null, arguments);
    }

    /**
     * Runs the statement on the database executor. The returned future completes exceptionally if
     * the statement (or the consumer) fails, in addition to the failure being logged.
     */
    public CompletableFuture<Void> executeStatement(@NonNull String query, @Nullable Consumer<ResultSet> consumer, @Nullable Object... arguments) {
        return runAsync("statement: " + query, statementTask(query, consumer, arguments));
    }

    /**
     * Like {@link #executeStatement(String, Consumer, Object...)}, but runs only after every earlier
     * ordered statement or batch with the same ordering key (normally a table name) has finished.
     * Use it for writes that must reach the database in submission order.
     */
    public CompletableFuture<Void> executeOrderedStatement(@NonNull String orderingKey, @NonNull String query, @Nullable Consumer<ResultSet> consumer, @Nullable Object... arguments) {
        return runOrdered(orderingKey, "statement: " + query, statementTask(query, consumer, arguments));
    }

    private SQLTask statementTask(String query, @Nullable Consumer<ResultSet> consumer, @Nullable Object[] arguments) {
        return () -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(query, PreparedStatement.RETURN_GENERATED_KEYS)) {
                bind(statement, arguments);
                statement.executeUpdate();

                if (consumer == null) {
                    return;
                }

                try (ResultSet result = statement.getGeneratedKeys()) {
                    consumer.accept(result);
                }
            }
        };
    }

    /**
     * Runs the query on the database executor. The returned future completes exceptionally if
     * the query (or the consumer) fails, in addition to the failure being logged.
     */
    public CompletableFuture<Void> executeQuery(@NonNull String query, @NonNull Consumer<ResultSet> consumer, @Nullable Object... arguments) {
        return runAsync("query: " + query, queryTask(query, consumer, arguments));
    }

    /**
     * Like {@link #executeQuery(String, Consumer, Object...)}, but first waits for every ordered write
     * with the same ordering key (normally a table name) that was submitted before this call, so the
     * query sees them. Reads never join the write chain, so they still run in parallel with each other.
     */
    public CompletableFuture<Void> executeQueryAfterWrites(@NonNull String orderingKey, @NonNull String query, @NonNull Consumer<ResultSet> consumer, @Nullable Object... arguments) {
        SQLTask task = queryTask(query, consumer, arguments);
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture<Void> pendingWrites = orderedTails.get(orderingKey.toLowerCase(Locale.ROOT));

        if (pendingWrites == null || pendingWrites.isDone()) {
            submit("query: " + query, task, future);
            return future;
        }

        pendingWrites.whenComplete((result, throwable) -> submit("query: " + query, task, future));
        return future;
    }

    private SQLTask queryTask(String query, Consumer<ResultSet> consumer, @Nullable Object[] arguments) {
        return () -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(query)) {
                bind(statement, arguments);

                try (ResultSet result = statement.executeQuery()) {
                    consumer.accept(result);
                }
            }
        };
    }

    public CompletableFuture<Void> executeBatch(@NonNull List<String> queries, @NonNull Consumer<Void> consumer) {
        return executeBatch(queries, new ArrayList<>(), consumer);
    }

    /**
     * Executes each query, grouping consecutive entries that share the same SQL text into a
     * single PreparedStatement via addBatch()/executeBatch() (a real JDBC batch, fewer round
     * trips) instead of one execute per statement. Entries are grouped only when adjacent so
     * execution order always matches the input order, even when queries differ statement to
     * statement. The whole batch is one transaction: it either fully commits, or is rolled back
     * and the consumer is not called.
     */
    public CompletableFuture<Void> executeBatch(@NonNull List<String> queries, @Nullable List<Object[]> parametersList, @NonNull Consumer<Void> consumer) {
        return runAsync("batch of " + queries.size() + " statement(s)", batchTask(queries, parametersList, consumer));
    }

    /**
     * Like {@link #executeBatch(List, List, Consumer)}, but runs only after every earlier ordered
     * statement or batch with the same ordering key (normally a table name) has finished.
     */
    public CompletableFuture<Void> executeOrderedBatch(@NonNull String orderingKey, @NonNull List<String> queries, @Nullable List<Object[]> parametersList, @NonNull Consumer<Void> consumer) {
        return runOrdered(orderingKey, "batch of " + queries.size() + " statement(s)", batchTask(queries, parametersList, consumer));
    }

    private SQLTask batchTask(List<String> queries, @Nullable List<Object[]> parametersList, Consumer<Void> consumer) {
        return () -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);

                try {
                    int i = 0;

                    while (i < queries.size()) {
                        String query = queries.get(i);
                        int runEnd = i;

                        while (runEnd < queries.size() && queries.get(runEnd).equals(query)) {
                            runEnd++;
                        }

                        try (PreparedStatement statement = connection.prepareStatement(query)) {
                            for (int j = i; j < runEnd; j++) {
                                if (parametersList != null && j < parametersList.size()) {
                                    bind(statement, parametersList.get(j));
                                }

                                statement.addBatch();
                            }

                            statement.executeBatch();
                        }

                        i = runEnd;
                    }

                    connection.commit();
                } catch (SQLException e) {
                    rollback(connection);
                    throw e;
                }
            }

            consumer.accept(null);
        };
    }

    public CompletableFuture<Boolean> transaction(@NonNull Consumer<Transaction> block) {
        return transaction(block, null);
    }

    /**
     * Runs block against a single shared connection with autoCommit disabled, committing only if
     * block returns normally and rolling back if it throws - so multiple save()/delete() calls made
     * against the given Transaction either all persist or none do. onComplete (if given) receives
     * true on commit, false on rollback. The returned future completes with the same value.
     * <p>
     * Do not call the non-transactional save()/delete() methods from inside block and wait on their
     * result: they need the database executor, which is busy running block.
     */
    public CompletableFuture<Boolean> transaction(@NonNull Consumer<Transaction> block, @Nullable Consumer<Boolean> onComplete) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        try {
            dbExecutor.execute(() -> {
                boolean committed = runTransaction(block);

                if (onComplete != null) {
                    try {
                        onComplete.accept(committed);
                    } catch (Exception e) {
                        logger.log(Level.SEVERE, "Transaction completion callback failed", e);
                    }
                }

                future.complete(committed);
            });
        } catch (RejectedExecutionException e) {
            logger.log(Level.WARNING, "Unable to run transaction, BormAPI is closed", e);
            future.complete(false);
        }

        return future;
    }

    private boolean runTransaction(Consumer<Transaction> block) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            Transaction transaction = new Transaction(connection);

            try {
                block.accept(transaction);
                connection.commit();
            } catch (Exception e) {
                rollback(connection);
                transaction.runRollbackHooks(logger);
                logger.log(Level.SEVERE, "Transaction rolled back", e);
                return false;
            }

            transaction.runAfterCommitHooks(logger);
            return true;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Unable to run transaction", e);
            return false;
        }
    }

    private void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Unable to roll back", e);
        }
    }

    private void bind(PreparedStatement statement, @Nullable Object[] arguments) throws SQLException {
        if (arguments == null) {
            return;
        }

        int index = 1;

        for (Object argument : arguments) {
            statement.setObject(index++, argument);
        }
    }

    private CompletableFuture<Void> runAsync(String description, SQLTask task) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        submit(description, task, future);
        return future;
    }

    /**
     * Runs the task after every earlier task with the same ordering key has finished (successfully or
     * not), so writes to one table always reach the database in the order they were submitted, even
     * when the executor runs on several threads. Tasks with different keys still run in parallel.
     */
    private CompletableFuture<Void> runOrdered(String orderingKey, String description, SQLTask task) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture<Void> previous = orderedTails.put(orderingKey.toLowerCase(Locale.ROOT), future);

        if (previous == null) {
            submit(description, task, future);
            return future;
        }

        previous.whenComplete((result, throwable) -> submit(description, task, future));
        return future;
    }

    private void submit(String description, SQLTask task, CompletableFuture<Void> future) {
        try {
            dbExecutor.execute(() -> {
                try {
                    task.run();
                    future.complete(null);
                } catch (Throwable e) {
                    logger.log(Level.SEVERE, "Error executing " + description, e);
                    future.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            logger.log(Level.WARNING, "Unable to run " + description + ", BormAPI is closed", e);
            future.completeExceptionally(e);
        }
    }

    /**
     * Stops accepting new work, waits briefly for queued work to finish, then closes the pool.
     */
    public void close() {
        if (dataSource.isClosed()) {
            return;
        }

        try {
            CompletableFuture.allOf(orderedTails.values().toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            logger.log(Level.WARNING, "Ordered database work did not finish cleanly before closing.", e);
        }

        dbExecutor.shutdown();

        try {
            if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warning("Pending database work did not finish in time and was cancelled.");
                dbExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            dbExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        dataSource.close();
    }

    public <T> void registerFieldProcessor(@NonNull TypeToken<T> type, @NonNull FieldProcessor<T> processor) {
        fieldProcessors.putIfAbsent(typeKey(type), processor);
    }

    public FieldProcessor<?> getFieldProcessor(@NonNull TypeToken<?> type) {
        FieldProcessor<?> processor = fieldProcessors.get(typeKey(type));

        if (processor == null) {
            return DEFAULT_FIELD_PROCESSOR;
        }

        return processor;
    }

    private String typeKey(TypeToken<?> type) {
        return type.getType().getTypeName().toLowerCase(Locale.ROOT);
    }

    @FunctionalInterface
    private interface SQLTask {

        void run() throws SQLException;

    }

}
