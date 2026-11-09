package net.bitbylogic.orm.data;

import lombok.Getter;
import lombok.NonNull;
import net.bitbylogic.orm.BormAPI;
import net.bitbylogic.orm.Transaction;
import net.bitbylogic.orm.annotation.Column;
import net.bitbylogic.orm.data.statements.BormStatements;
import net.bitbylogic.orm.processor.FieldProcessor;
import net.bitbylogic.orm.processor.impl.DefaultFieldProcessor;
import net.bitbylogic.orm.query.Query;
import net.bitbylogic.orm.redis.BormRedisHook;
import net.bitbylogic.orm.redis.BormRedisUpdateType;
import net.bitbylogic.orm.util.SqlIdentifier;
import net.bitbylogic.utils.HashMapUtil;
import net.bitbylogic.utils.ListUtil;
import net.bitbylogic.utils.Pair;
import net.bitbylogic.utils.StringProcessor;
import net.bitbylogic.utils.reflection.NamedParameter;
import net.bitbylogic.utils.reflection.ReflectionUtil;
import net.bitbylogic.utils.reflection.TypeToken;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;

@Getter
public class BormTable<O extends BormObject> {

    private final BormAPI bormAPI;

    private final String table;
    private final boolean loadData;
    private final Class<O> objectClass;

    private final ConcurrentHashMap<Object, O> dataMap;
    private final BormStatements<O> statements;

    private Constructor<O> objectConstructor;

    public BormTable(BormAPI bormAPI, Class<O> objectClass, String table, boolean loadData) {
        if (!SqlIdentifier.isValid(table)) {
            throw new IllegalArgumentException("Invalid table name \"" + table + "\", only letters, digits and underscores are allowed.");
        }

        this.bormAPI = bormAPI;
        this.table = table;
        this.loadData = loadData;
        this.objectClass = objectClass;
        this.dataMap = new ConcurrentHashMap<>();
        this.statements = bormAPI.getType().getStatements(bormAPI, table);

        try {
            Constructor<O> emptyConstructor = ReflectionUtil.findConstructor(objectClass);

            if (emptyConstructor == null) {
                log("Unable to create table, missing main constructor.");
                return;
            }

            O tempObject = emptyConstructor.newInstance();
            statements.loadColumnData(tempObject, new ArrayList<>());

            List<NamedParameter> namedParameters = statements.getColumnData().stream().map(data -> data.asNamedParameter(null)).toList();

            objectConstructor = ReflectionUtil.findNamedConstructor(objectClass, namedParameters.toArray(new NamedParameter[]{}));

            if (objectConstructor == null) {
                log("Unable to create table, missing main constructor.");
                return;
            }
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            bormAPI.getLogger().log(Level.SEVERE, "(" + getClass().getSimpleName() + "): Unable to create table.", e);
        }
    }

    public BormTable(BormAPI bormAPI, Class<O> objectClass, String table) {
        this(bormAPI, objectClass, table, false);
    }

    public void loadData(@NonNull Runnable completeRunnable) {
        log("Retrieving data from database...");

        bormAPI.executeQueryAfterWrites(table, String.format("SELECT * FROM %s;", table), result -> loadObjects(result, objects -> {
            Map<Object, O> freshData = new LinkedHashMap<>();

            for (O data : objects) {
                freshData.put(statements.getId(data), data);
            }

            dataMap.keySet().removeIf(id -> !freshData.containsKey(id));

            freshData.forEach((id, data) -> {
                dataMap.put(id, data);
                data.setOwningTable(this);
                onDataAdded(data);
            });

            log("Finished retrieving data, loaded " + dataMap.size() + " object(s).");

            completeRunnable.run();
            onDataLoaded();
        }));
    }

    public void loadDataByField(@NonNull String fieldName, @NonNull Object object, @NonNull Runnable completeRunnable) {
        statements.getColumnData(fieldName).ifPresentOrElse(columnData -> {
            FieldProcessor processor = bormAPI.getFieldProcessor(TypeToken.asTypeToken(columnData.getField().getGenericType()));

            Object value = processor.processTo(object);
            String query = String.format("SELECT * FROM %s WHERE %s=?;", table, columnData.getName());

            bormAPI.executeQueryAfterWrites(table, query, result -> loadObjects(result, objects -> {
                for (O data : objects) {
                    dataMap.put(statements.getId(data), data);
                    data.setOwningTable(this);
                    onDataAdded(data);
                }

                completeRunnable.run();
            }), value);
        }, () -> {
            bormAPI.getLogger().warning("Unable to find column for field: " + fieldName + " in table: " + table);
            completeRunnable.run();
        });
    }

    /**
     * Reads every remaining row of the result set and builds its object, handing the consumer a
     * single list - in row order - once every row (including any foreign data it needed) has
     * finished loading. Rows that fail to load are logged and left out of the list.
     */
    public void loadObjects(@NonNull ResultSet result, @NonNull Consumer<List<O>> consumer) {
        List<O> loaded = new ArrayList<>();
        AtomicInteger pendingRows = new AtomicInteger(1);

        Runnable rowFinished = () -> {
            if (pendingRows.decrementAndGet() != 0) {
                return;
            }

            List<O> ordered;

            synchronized (loaded) {
                ordered = loaded.stream().filter(Objects::nonNull).toList();
            }

            consumer.accept(ordered);
        };

        try {
            while (result.next()) {
                int index;

                synchronized (loaded) {
                    index = loaded.size();
                    loaded.add(null);
                }

                pendingRows.incrementAndGet();

                try {
                    loadObject(result, optional -> {
                        optional.ifPresent(data -> {
                            synchronized (loaded) {
                                loaded.set(index, data);
                            }
                        });

                        rowFinished.run();
                    });
                } catch (SQLException e) {
                    bormAPI.getLogger().log(Level.SEVERE, "Failed to read a row from table " + table, e);
                    rowFinished.run();
                    break;
                }
            }
        } catch (SQLException e) {
            bormAPI.getLogger().log(Level.SEVERE, "Failed to read rows from table " + table, e);
        }

        rowFinished.run();
    }

    public void onDataLoaded() {
    }

    public void onDataDeleted(@NonNull O object) {
    }

    public void onDataAdded(@NonNull O object) {
    }

    public void onDataUpdated(@NonNull O object) {
    }

    /**
     * A live, read-only view of the in-memory cache. Use {@link #add}, {@link #unload} and
     * {@link #unloadAll} to change it.
     */
    public Map<Object, O> getDataMap() {
        return Collections.unmodifiableMap(dataMap);
    }

    public Query<O> query() {
        return new Query<>(this);
    }

    public void add(@NonNull O object) {
        add(object, true);
    }

    public void add(@NonNull O object, boolean save) {
        ColumnData primaryKeyData = statements.getPrimaryKeyData();

        if (primaryKeyData != null && primaryKeyData.getColumn().autoIncrement() && statements.isUnassigned(statements.getId(object))) {
            object.setOwningTable(this);

            if (!save) {
                log("Auto-increment objects get their id when saved, so they can't be cached without saving.");
                return;
            }

            save(object, saved -> {
                if (dataMap.putIfAbsent(statements.getId(object), object) == null) {
                    onDataAdded(object);
                }
            });
            return;
        }

        if (dataMap.containsKey(statements.getId(object))) {
            return;
        }

        dataMap.put(statements.getId(object), object);
        object.setOwningTable(this);
        onDataAdded(object);

        if (!save) {
            return;
        }

        save(object);
    }

    /**
     * Removes this object from the in-memory cache without touching the database - the counterpart
     * to lazy loading. Call save() first if unpersisted changes should survive the eviction, e.g.
     * {@code player.save(); table.unload(player);} on a quit event.
     */
    public void unload(@NonNull Object id) {
        dataMap.remove(id);
    }

    public void unload(@NonNull O object) {
        unload(statements.getId(object));
    }

    public void unloadAll() {
        dataMap.clear();
    }

    public void save(@NonNull O object) {
        save(object, (Consumer<Optional<ResultSet>>) null);
    }

    public void save(@NonNull O object, @Nullable Consumer<Optional<ResultSet>> callback) {
        Pair<String, List<Object>> saveStatement = statements.getDataSaveStatement(object);

        bormAPI.executeOrderedStatement(table, saveStatement.getKey(), result -> {
            if (result == null) {
                if (callback != null) {
                    callback.accept(Optional.empty());
                }

                BormRedisHook redisHook = bormAPI.getRedisHook();
                if (redisHook == null) {
                    return;
                }

                redisHook.sendChange(BormRedisUpdateType.SAVE, table, statements.getId(object).toString());
                return;
            }

            try {
                boolean hasGeneratedKey = result.next();

                statements.getColumnData().forEach(columnData -> {
                    if (!columnData.getColumn().autoIncrement() || !hasGeneratedKey) {
                        return;
                    }

                    try {
                        applyGeneratedKey(columnData, object, result);
                    } catch (SQLException | IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            onDataUpdated(object);

            if (callback != null) {
                callback.accept(Optional.of(result));
            }

            BormRedisHook redisHook = bormAPI.getRedisHook();
            if (redisHook == null) {
                return;
            }

            redisHook.sendChange(BormRedisUpdateType.SAVE, table, statements.getId(object).toString());
        }, saveStatement.getValue().toArray());
    }

    /**
     * Saves this object as part of an in-progress transaction instead of its own connection, so it
     * only persists if the whole transaction commits. Executes synchronously against the
     * transaction's connection - call from within the block passed to {@link BormAPI#transaction}.
     */
    public void save(@NonNull O object, @NonNull Transaction transaction) throws SQLException {
        Pair<String, List<Object>> saveStatement = statements.getDataSaveStatement(object);

        transaction.execute(saveStatement.getKey(), result -> {
            boolean hasGeneratedKey;

            try {
                hasGeneratedKey = result != null && result.next();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            boolean finalHasGeneratedKey = hasGeneratedKey;
            statements.getColumnData().forEach(columnData -> {
                if (!columnData.getColumn().autoIncrement() || !finalHasGeneratedKey) {
                    return;
                }

                try {
                    applyGeneratedKey(columnData, object, result);
                } catch (SQLException | IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            });

            transaction.addAfterCommitHook(() -> {
                onDataUpdated(object);

                BormRedisHook redisHook = bormAPI.getRedisHook();
                if (redisHook == null) {
                    return;
                }

                redisHook.sendChange(BormRedisUpdateType.SAVE, table, statements.getId(object).toString());
            });
        }, saveStatement.getValue().toArray());
    }

    public void saveAll(@Nullable Consumer<Void> callback) {
        if (dataMap.isEmpty()) {
            if (callback != null) {
                callback.accept(null);
            }
            return;
        }

        List<String> queries = new ArrayList<>();
        List<Object[]> parametersList = new ArrayList<>();

        getDataMap().values().forEach(o -> {
            Pair<String, List<Object>> saveStatement = getStatements().getDataSaveStatement(o);
            queries.add(saveStatement.getKey());
            parametersList.add(saveStatement.getValue().toArray());
        });

        bormAPI.executeOrderedBatch(table, queries, parametersList, result -> {
            BormRedisHook redisHook = bormAPI.getRedisHook();
            if (redisHook != null) {
                redisHook.sendChange(BormRedisUpdateType.SAVE_ALL, table, "");
            }

            if (callback != null) {
                callback.accept(null);
            }
        });
    }

    /**
     * Deletes all the entries in the in-memory cache and the corresponding records
     * from the associated database table. This method clears the internal data map
     * and executes a SQL statement to remove all rows from the table.
     */
    public void deleteAll() {
        dataMap.clear();
        bormAPI.executeOrderedStatement(table, statements.getTableClearStatement(), null);
    }

    public void delete(@NonNull O object) {
        if (statements.getPrimaryKeyData() == null) {
            log("No primary key for object, failed to delete!");
            return;
        }

        if (!dataMap.containsKey(statements.getId(object))) {
            return;
        }

        dataMap.remove(statements.getId(object));

        for (ColumnData columnData : statements.getColumnData()) {
            if (columnData.getForeignTable() == null || !columnData.getColumn().cascadeDelete()) {
                continue;
            }

            try {
                Field field = columnData.getField();

                BormTable foreignTable = columnData.getForeignTable();
                Object foreignObject = field.get(object);

                if (foreignObject instanceof List<?> list) {
                    if (list.isEmpty()) {
                        continue;
                    }

                    List<BormObject> dataList = (List<BormObject>) foreignObject;
                    dataList.forEach(foreignTable::delete);
                    continue;
                }

                if (foreignObject instanceof HashMap<?, ?> hashMap) {
                    if (hashMap.isEmpty()) {
                        continue;
                    }

                    HashMap<?, BormObject> dataMap = (HashMap<?, BormObject>) foreignObject;
                    dataMap.values().forEach(foreignTable::delete);
                    continue;
                }

                if (!(foreignObject instanceof BormObject)) {
                    continue;
                }

                foreignTable.delete((BormObject) foreignObject);
            } catch (Exception e) {
                bormAPI.getLogger().log(Level.SEVERE, "(" + getClass().getSimpleName() + "): Unable to delete foreign data.", e);
            }
        }

        Pair<String, List<Object>> deleteStatement = statements.getDataDeleteStatement(object);

        bormAPI.executeOrderedStatement(table, deleteStatement.getKey(), rs -> {
            onDataDeleted(object);

            BormRedisHook redisHook = bormAPI.getRedisHook();
            if (redisHook == null) {
                return;
            }

            redisHook.sendChange(BormRedisUpdateType.DELETE, table, statements.getId(object).toString());
        }, deleteStatement.getValue().toArray());
    }

    /**
     * Deletes this object as part of an in-progress transaction instead of its own connection, so it
     * only persists if the whole transaction commits. Cascaded foreign deletes are threaded through
     * the same transaction, so a cascade and its parent either all delete together or none do.
     */
    public void delete(@NonNull O object, @NonNull Transaction transaction) throws SQLException {
        if (statements.getPrimaryKeyData() == null) {
            log("No primary key for object, failed to delete!");
            return;
        }

        Object id = statements.getId(object);

        if (!dataMap.containsKey(id)) {
            return;
        }

        dataMap.remove(id);
        transaction.addRollbackHook(() -> dataMap.putIfAbsent(id, object));

        for (ColumnData columnData : statements.getColumnData()) {
            if (columnData.getForeignTable() == null || !columnData.getColumn().cascadeDelete()) {
                continue;
            }

            try {
                Field field = columnData.getField();

                BormTable foreignTable = columnData.getForeignTable();
                Object foreignObject = field.get(object);

                if (foreignObject instanceof List<?> list) {
                    if (list.isEmpty()) {
                        continue;
                    }

                    List<BormObject> dataList = (List<BormObject>) foreignObject;

                    for (BormObject child : dataList) {
                        foreignTable.delete(child, transaction);
                    }

                    continue;
                }

                if (foreignObject instanceof HashMap<?, ?> hashMap) {
                    if (hashMap.isEmpty()) {
                        continue;
                    }

                    HashMap<?, BormObject> dataMap = (HashMap<?, BormObject>) foreignObject;

                    for (BormObject child : dataMap.values()) {
                        foreignTable.delete(child, transaction);
                    }

                    continue;
                }

                if (!(foreignObject instanceof BormObject)) {
                    continue;
                }

                foreignTable.delete((BormObject) foreignObject, transaction);
            } catch (SQLException e) {
                throw e;
            } catch (Exception e) {
                bormAPI.getLogger().log(Level.SEVERE, "(" + getClass().getSimpleName() + "): Unable to delete foreign data.", e);
            }
        }

        Pair<String, List<Object>> deleteStatement = statements.getDataDeleteStatement(object);

        transaction.execute(deleteStatement.getKey(), rs -> {
            transaction.addAfterCommitHook(() -> {
                onDataDeleted(object);

                BormRedisHook redisHook = bormAPI.getRedisHook();
                if (redisHook == null) {
                    return;
                }

                redisHook.sendChange(BormRedisUpdateType.DELETE, table, statements.getId(object).toString());
            });
        }, deleteStatement.getValue().toArray());
    }

    protected void loadPendingData(@NonNull Pair<Field, Object> key, @NonNull Object object, Consumer<Object> consumer) {
        Field field = key.getKey();
        Object value = key.getValue();

        Class<?> primaryKeyType = statements.getPrimaryKeyData().getField().getType();
        Class<?> fieldType = field.getType();

        if (value == null || ((value instanceof String) && ((String) value).trim().isEmpty())) {
            consumer.accept(
                    List.class.isAssignableFrom(fieldType) ? new ArrayList<>() :
                            Map.class.isAssignableFrom(fieldType) ? new HashMap<>() : null);
            return;
        }

        if (BormObject.class.isAssignableFrom(fieldType)) {
            if (value instanceof String) {
                value = StringProcessor.findAndProcess(primaryKeyType, (String) value);
            }

            Object foreignId = value;

            getDataFromDB(foreignId, true, true, o -> {
                if (o.isEmpty()) {
                    log("Missing foreign object " + foreignId + " for field " + field.getName() + ", it will be null.");
                }

                consumer.accept(o.orElse(null));
            });
            return;
        }

        if (List.class.isAssignableFrom(fieldType)) {
            if (!(value instanceof String)) {
                log("Unable to process field: " + field.getName() + " for class " + object.getClass().getSimpleName() + ".");
                consumer.accept(new ArrayList<>());
                return;
            }

            List<Object> rawList = (List<Object>) ListUtil.stringToList((String) value);

            if (rawList.isEmpty()) {
                consumer.accept(new ArrayList<>());
                return;
            }

            List<Object> ids = new ArrayList<>();

            for (Object id : rawList) {
                if (!(id instanceof String)) {
                    log("Unable to process item in list: " + id + " for class " + object.getClass().getSimpleName() + ".");
                    continue;
                }

                ids.add(StringProcessor.findAndProcess(primaryKeyType, (String) id));
            }

            getDataFromDBBatch(ids, true, true, results -> {
                List<O> newList = new ArrayList<>();

                for (Object id : ids) {
                    O data = results.get(id);

                    if (data != null) {
                        newList.add(data);
                    }
                }

                consumer.accept(newList);
            });
            return;
        }

        if (Map.class.isAssignableFrom(fieldType) || !(value instanceof String)) {
            log("Unable to process field: " + field.getName() + " for class " + object.getClass().getSimpleName() + ".");
            consumer.accept(new HashMap<>());
            return;
        }

        Map<Object, Object> rawMap = HashMapUtil.mapFromString(null, (String) value);

        if (rawMap.isEmpty()) {
            consumer.accept(new HashMap<>());
            return;
        }

        Map<Object, Object> keyToId = new LinkedHashMap<>();

        rawMap.forEach((key1, id) -> {
            if (!(id instanceof String)) {
                log("Unable to process item in map: " + id + " for class " + object.getClass().getSimpleName() + ".");
                return;
            }

            keyToId.put(key1, StringProcessor.findAndProcess(primaryKeyType, (String) id));
        });

        getDataFromDBBatch(new ArrayList<>(keyToId.values()), true, true, results -> {
            HashMap<Object, O> newMap = new HashMap<>();

            keyToId.forEach((key1, id) -> {
                O data = results.get(id);

                if (data != null) {
                    newMap.put(key1, data);
                }
            });

            consumer.accept(newMap);
        });
    }

    /**
     * Builds the object for the result set's current row. The consumer is called exactly once -
     * with an empty optional if the object couldn't be built (the reason is logged).
     */
    public void loadObject(ResultSet result, Consumer<Optional<O>> consumer) throws SQLException {
        List<ColumnData> columns = statements.getColumnData();

        if (objectConstructor == null || columns.isEmpty()) {
            log("Unable to load object, the table has no usable constructor or columns.");
            consumer.accept(Optional.empty());
            return;
        }

        List<NamedParameter> namedParameters = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger remainingColumns = new AtomicInteger(columns.size());
        AtomicBoolean finished = new AtomicBoolean();

        Consumer<Optional<O>> finishOnce = optional -> {
            if (finished.compareAndSet(false, true)) {
                consumer.accept(optional);
            }
        };

        Runnable columnFinished = () -> {
            if (remainingColumns.decrementAndGet() != 0) {
                return;
            }

            try {
                finishOnce.accept(Optional.of(ReflectionUtil.callConstructor(objectConstructor, namedParameters.toArray(new NamedParameter[]{}))));
            } catch (Exception e) {
                bormAPI.getLogger().log(Level.SEVERE, "(" + getClass().getSimpleName() + "): Unable to load object.", e);
                finishOnce.accept(Optional.empty());
            }
        };

        for (ColumnData columnData : columns) {
            Column statementData = columnData.getColumn();
            FieldProcessor processor = bormAPI.getFieldProcessor(TypeToken.asTypeToken(columnData.getField().getGenericType()));

            Object object = result.getObject(columnData.getName());
            Class<?> fieldTypeClass = columnData.getField().getType();

            try {
                if (fieldTypeClass.isEnum()) {
                    for (Object enumConstant : fieldTypeClass.getEnumConstants()) {
                        if (!((Enum<?>) enumConstant).name().equalsIgnoreCase(String.valueOf(object))) {
                            continue;
                        }

                        object = enumConstant;
                        break;
                    }
                } else {
                    object = processor.processFrom(object);

                    if (fieldTypeClass != String.class &&
                            processor instanceof DefaultFieldProcessor &&
                            object instanceof String string) {
                        object = StringProcessor.findAndProcess(fieldTypeClass, string);
                    }

                    if (fieldTypeClass == boolean.class || fieldTypeClass == Boolean.class) {
                        object = StringProcessor.findAndProcess(fieldTypeClass, String.valueOf(object));
                    }
                }
            } catch (RuntimeException e) {
                bormAPI.getLogger().log(Level.SEVERE, "(" + getClass().getSimpleName() + "): Unable to read column " + columnData.getName() + ", skipping the row.", e);
                finishOnce.accept(Optional.empty());
                return;
            }

            if (statementData.foreignTable().isEmpty()) {
                namedParameters.add(columnData.asNamedParameter(object));
                columnFinished.run();
                continue;
            }

            BormTable<?> foreignTable = bormAPI.getTable(statementData.foreignTable());

            if (foreignTable == null) {
                log("Unable to load object, missing foreign table: " + statementData.foreignTable());
                finishOnce.accept(Optional.empty());
                return;
            }

            foreignTable.loadPendingData(new Pair<>(columnData.getField(), object), foreignTable, value -> {
                namedParameters.add(columnData.asNamedParameter(value));
                columnFinished.run();
            });
        }
    }

    public Optional<O> getDataById(@NonNull Object id) {
        if(!statements.getPrimaryKeyData().getField().getType().equals(String.class) && id instanceof String) {
            return dataMap.entrySet().stream().filter(entry -> entry.getKey().toString().equalsIgnoreCase((String) id)).map(Map.Entry::getValue).findFirst();
        }

        return Optional.ofNullable(dataMap.get(id));
    }

    public void getDataFromDB(@NonNull Object id, boolean checkCache, @NonNull Consumer<Optional<O>> consumer) {
        getDataFromDB(id, checkCache, true, consumer);
    }

    public void getDataFromDB(@NonNull Object id, boolean checkCache, boolean cache, @NonNull Consumer<Optional<O>> consumer) {
        if (checkCache) {
            Optional<O> optionalValue = getDataById(id);

            if (optionalValue.isPresent()) {
                consumer.accept(optionalValue);
                return;
            }
        }

        fetchById(id, fetched -> {
            if (fetched.isEmpty()) {
                consumer.accept(Optional.empty());
                return;
            }

            O data = fetched.get();

            if (cache && dataMap.putIfAbsent(statements.getId(data), data) == null) {
                data.setOwningTable(this);
                onDataAdded(data);
            }

            consumer.accept(Optional.of(data));
        });
    }

    /**
     * Re-reads a single row from the database and replaces the cached object with the fresh copy,
     * without a window where the entry is missing from the cache. If the row no longer exists the
     * entry is removed from the cache.
     */
    public void refresh(@NonNull Object id, @Nullable Runnable completeRunnable) {
        fetchById(id, fetched -> {
            if (fetched.isEmpty()) {
                dataMap.remove(id);
            } else {
                O data = fetched.get();

                dataMap.put(statements.getId(data), data);
                data.setOwningTable(this);
                onDataUpdated(data);
            }

            if (completeRunnable != null) {
                completeRunnable.run();
            }
        });
    }

    private void fetchById(@NonNull Object id, @NonNull Consumer<Optional<O>> consumer) {
        if (statements.getPrimaryKeyData() == null) {
            consumer.accept(Optional.empty());
            return;
        }

        bormAPI.executeQueryAfterWrites(table, String.format("SELECT * FROM %s WHERE %s = ?;", table, statements.getPrimaryKeyData().getName()), result -> {
            try {
                if (result == null || !result.next()) {
                    consumer.accept(Optional.empty());
                    return;
                }

                loadObject(result, consumer);
            } catch (SQLException exception) {
                bormAPI.getLogger().log(Level.SEVERE, "(" + getClass().getSimpleName() + "): Failed to read row " + id, exception);
                consumer.accept(Optional.empty());
            }
        }, id);
    }

    /**
     * Resolves multiple ids in a single query (falling back to cached entries where possible)
     * instead of issuing one query per id, to avoid N+1 lookups when loading List/Map foreign fields.
     */
    public void getDataFromDBBatch(@NonNull List<Object> ids, boolean checkCache, boolean cache, @NonNull Consumer<Map<Object, O>> consumer) {
        Map<Object, O> results = new LinkedHashMap<>();
        List<Object> missingIds = new ArrayList<>();

        for (Object id : ids) {
            if (checkCache) {
                Optional<O> cachedValue = getDataById(id);

                if (cachedValue.isPresent()) {
                    results.put(id, cachedValue.get());
                    continue;
                }
            }

            missingIds.add(id);
        }

        if (missingIds.isEmpty() || statements.getPrimaryKeyData() == null) {
            consumer.accept(results);
            return;
        }

        String placeholders = String.join(", ", Collections.nCopies(missingIds.size(), "?"));
        String query = String.format("SELECT * FROM %s WHERE %s IN (%s);", table, statements.getPrimaryKeyData().getName(), placeholders);

        bormAPI.executeQueryAfterWrites(table, query, result -> loadObjects(result, objects -> {
            for (O data : objects) {
                Object id = statements.getId(data);
                results.put(id, data);

                if (cache && dataMap.putIfAbsent(id, data) == null) {
                    data.setOwningTable(this);
                    onDataAdded(data);
                }
            }

            consumer.accept(results);
        }), missingIds.toArray());
    }

    private void applyGeneratedKey(ColumnData columnData, O object, ResultSet result) throws SQLException, IllegalAccessException {
        Field field = columnData.getField();

        if (!statements.isUnassigned(field.get(object))) {
            return;
        }

        Class<?> keyType = field.getType();

        if (keyType == int.class || keyType == Integer.class) {
            field.set(object, result.getInt(1));
            return;
        }

        if (keyType == long.class || keyType == Long.class) {
            field.set(object, result.getLong(1));
            return;
        }

        log("Unsupported auto-increment key type " + keyType.getSimpleName() + " on field " + field.getName() + ", use int or long.");
    }

    private void log(@NonNull String message) {
        bormAPI.getLogger().info("(" + getClass().getSimpleName() + "): " + message);
    }

}
