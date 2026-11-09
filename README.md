<div align="center">

## Bit's ORM (BORM)

An API for mapping Java objects to SQL, enabling seamless saving and loading of data to and from tables.

</div>

## Features
* Simple setup, only requires 2 classes to use
* Foreign table support, with cascading deletes
* Easily handle complex objects with Field Processors
* Automatic column data type inference
* MySQL and SQLite support natively
* Atomic multi-write transactions
* A small filter/sort/limit query builder
* Lazy loading and unloading for large tables
* Seamless data synchronization using Redis (Bit's RPS)

## Requirements
* Java 21
* MySQL 8+ or SQLite
* Your project must be compiled with the `-parameters` flag (see below)
* Optional: [Bit's RPS](https://github.com/BitByLogics/Bits-RPS) for Redis synchronization, and the Paper API if you use `BukkitLocationProcessor`. Neither is pulled in automatically.

## IMPORTANT
In order for BORM to work, you must add the parameters flag when compiling!

### For Maven
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.13.0</version>
    <configuration>
        <source>${java.version}</source>
        <target>${java.version}</target>
        <parameters>true</parameters>
    </configuration>
</plugin>
```

## Installation

BORM is published on the Minebit repository.

### Maven
```xml
<repository>
    <id>minebit</id>
    <url>https://repo.minebit.dev/releases</url>
</repository>
```

```xml
<dependency>
    <groupId>net.bitbylogic</groupId>
    <artifactId>bits-orm</artifactId>
    <version>2.0.1</version>
</dependency>
```

### Gradle
```kotlin
repositories {
    maven("https://repo.minebit.dev/releases")
}

dependencies {
    implementation("net.bitbylogic:bits-orm:2.0.1")
}
```

BORM is a library: its jar contains only BORM's own classes, and HikariCP, Bit's Utils and the SQLite and MySQL drivers come in as normal transitive dependencies. Neither the Paper API nor Bit's RPS is needed unless you use `BukkitLocationProcessor` or the Redis hook.

If you ship BORM inside a plugin, shade it and relocate it into your own package. This configuration is tested:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <version>3.6.0</version>
    <executions>
        <execution>
            <phase>package</phase>
            <goals>
                <goal>shade</goal>
            </goals>
            <configuration>
                <transformers>
                    <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                </transformers>
                <relocations>
                    <relocation>
                        <pattern>net.bitbylogic.orm</pattern>
                        <shadedPattern>your.plugin.libs.orm</shadedPattern>
                    </relocation>
                    <relocation>
                        <pattern>net.bitbylogic.utils</pattern>
                        <shadedPattern>your.plugin.libs.utils</shadedPattern>
                    </relocation>
                    <relocation>
                        <pattern>com.zaxxer.hikari</pattern>
                        <shadedPattern>your.plugin.libs.hikari</shadedPattern>
                    </relocation>
                </relocations>
            </configuration>
        </execution>
    </executions>
</plugin>
```

The `ServicesResourceTransformer` merges the drivers' service files so both JDBC drivers stay registered. Leave the JDBC drivers themselves unrelocated.

## Usage
### Using `BORM` is simple! Below is an example usage of the API and its capabilities.

BormAPI has three constructors:

* BormAPI(String address, String database, String port, String username, String password) - MySQL
* BormAPI(HikariConfig config) - MySQL, or SQLite when the JDBC URL contains `sqlite`
* BormAPI(File databaseFile) - SQLite, the file and its parent folders are created if needed

In these examples we'll be using the File constructor, which uses SQLite.

### First, initialize your instance of the BormAPI. This class is essential and will handle all BormTables
as well as executing the actual SQL statements.

```java
import net.bitbylogic.orm.BormAPI;

public class Example {

    private final BormAPI bormAPI;

    public Example() {
        this.bormAPI = new BormAPI(new File("database.sql"));
    }

    public static void main(String[] args) {
        new Example();
    }

}
```

That's it! The BormAPI class is now initialized, we can now utilize it to create our table and data object!

When you're done (for example in a plugin's `onDisable`), call `bormAPI.close()`. It lets queued writes finish before closing the connection pool.

### Next, we'll create our Java object that will map to the SQL table.

To create an object that will be mapped to SQL, first extend the BormObject class. Next, create fields that you want to be stored in the table.
The order of the fields determines the order of the columns in the table. For example, id will appear before creationDate.

The column annotation is your main entrypoint to defining that a field is associated with a column. By annotating a field with it, when the class
is parsed by the BormTable it will identify relevant information from that field and create a database column. By default, the columns name will
be the fields name exactly, so creationDate will make the column creationDate! However, you can modify this by specifying a new name in the Column
annotation. Any fields missing the Column annotation will be ignored by the table.

Table and column names are placed into SQL text, so they may only contain letters, digits and underscores (and must not start with a digit). A table with an invalid name is rejected, and a field with an invalid column name is skipped with a warning.

The annotation has various attributes that can be defined which changes how the column will be created. You can find a list of Column attributes after this example.

**IMPORTANT** `In order for BORM to work, the object MUST have an empty constructor and a constructor with all fields defined with the column annotation!`

```java
import net.bitbylogic.orm.annotation.Column;
import net.bitbylogic.orm.data.BormObject;

import java.util.UUID;

public class ExampleUser extends BormObject {

    @Column(primaryKey = true)
    private UUID id;

    @Column
    private long creationDate;

    public ExampleUser() {

    }

    public ExampleUser(UUID id) {
        this.id = id;
        this.creationDate = System.currentTimeMillis();
    }

    public ExampleUser(UUID id, long creationDate) {
        this.id = id;
        this.creationDate = creationDate;
    }

}
```

### Next, create your BormTable and register it via the BormAPI

The constructor of BormTable requires 4 objects:
* BormAPI - An instance of the BormAPI to allow the table to execute statements
* BormObject Class Reference - Used to parse and load field information
* Table Name - The name of the sql table, can be anything
* Load Data Boolean - Whether to load all data from the table into the mapped Java object

```java
import net.bitbylogic.orm.BormAPI;
import net.bitbylogic.orm.data.BormTable;

public class ExampleTable extends BormTable<ExampleUser> {

    public ExampleTable(BormAPI bormAPI) {
        super(bormAPI, ExampleUser.class, "example_users", true);
    }

}
```

```java
import net.bitbylogic.orm.BormAPI;

public class Example {

    private final BormAPI bormAPI;

    private ExampleTable exampleTable;

    public Example() {
        this.bormAPI = new BormAPI(new File("database.sql"));

        bormAPI.registerTable(ExampleTable.class, exampleTable -> this.exampleTable = exampleTable);
    }

    public static void main(String[] args) {
        new Example();
    }

}
```

Registration is asynchronous: the callback runs once the table exists and (if requested) its data is loaded. If a table can't be created, for example because its object class has no usable constructor, an error is logged and the callback is never called.

### Lazy loading and unloading

If you omit the `Load Data Boolean` (the 3-arg `BormTable` constructor), the table starts **empty** rather than eagerly loading every row - the right default for a table that could grow to thousands of entries, since eagerly loading all of it would mean startup time and memory use scale with total historical row count instead of how many are actually in use.

Data still loads automatically the first time it's asked for, via `getDataFromDB`/`getDataFromDBBatch` - and it's cached from then on, the same as eager loading. What changes is that entries also need an explicit way to leave the cache once they're no longer needed, since nothing evicts them on its own:

```java
// On join: load (or create) and use.
exampleTable.getDataFromDB(playerId, true, true, optionalUser -> {
    ExampleUser user = optionalUser.orElseGet(() -> new ExampleUser(playerId));
    exampleTable.add(user, false);
});

// On quit: persist, then drop from memory.
exampleUser.save();
exampleTable.unload(exampleUser);
```

`unload()` only removes the object from the in-memory cache - it never touches the database. If you have unsaved changes you want to keep, call `save()` first. `unloadAll()` empties the whole cache.

`getDataMap()` is a read-only, live view of the cache. Use `add`, `unload` and `unloadAll` to change it.

**Note:** the 4-arg constructor's `true`/`false` still means exactly what it always has (eagerly load everything vs. load nothing until manually pulled) - only the 3-arg constructor's default changed, from `true` to `false`.

### Adding/Removing data from the table

```java
import net.bitbylogic.orm.BormAPI;

import java.util.UUID;

public class Example {

    private final BormAPI bormAPI;

    private ExampleTable exampleTable;

    public Example() {
        this.bormAPI = new BormAPI(new File("database.sql"));

        bormAPI.registerTable(ExampleTable.class, exampleTable -> {
            this.exampleTable = exampleTable;

            ExampleUser exampleUser = new ExampleUser(UUID.randomUUID());
            exampleTable.add(exampleUser);

            // Once a mapped object is added to the table, convenient methods can be used to save/delete the object
            exampleUser.save();
            exampleUser.delete();
        });
    }

    public static void main(String[] args) {
        new Example();
    }

}
```

### Threading, ordering and errors

Everything that touches the database runs on BORM's own threads, so **callbacks do not run on your server's main thread**. On Paper or Folia, schedule back onto the right thread before touching the world or players.

* `execute*` methods and `transaction` return a `CompletableFuture`. It completes exceptionally if the work failed, and the failure is also logged together with the SQL.
* Writes to one table (`save`, `saveAll`, `delete`, `deleteAll`) reach the database in the order you submitted them, even on MySQL, where a connection pool runs work in parallel. Writes to different tables run in parallel.
* Reads on a table (`getDataFromDB`, `getDataFromDBBatch`, `loadData`, `loadDataByField` and `query()`) wait for writes to the same table that were submitted before them, so a query always sees your earlier `add()`/`save()`.
* Transactions are not ordered against plain saves. If you mix them on the same rows, wait on the returned future first.
* Don't block waiting on the result of another `save()` or `delete()` from inside a `transaction` block. The block holds a database thread.

### Foreign tables

A field can reference another `BormObject` (or a `List`/`Map` of them) by setting `foreignTable` to that object's table name. BORM stores the id(s) of the referenced object(s) and resolves them back into the full object(s) when loading.

Tables can be registered in any order. If a table's foreign tables aren't registered yet, BORM queues it and loads it automatically once every one of them has loaded. If a referenced row no longer exists, the field loads as `null` (or is left out of the list) and a message is logged.

```java
import net.bitbylogic.orm.annotation.Column;
import net.bitbylogic.orm.data.BormObject;

import java.util.UUID;

public class ExamplePet extends BormObject {

    @Column(primaryKey = true)
    private UUID id;

    @Column
    private String name;

    public ExamplePet() {
    }

    public ExamplePet(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

}
```

```java
import net.bitbylogic.orm.BormAPI;
import net.bitbylogic.orm.data.BormTable;

public class ExamplePetTable extends BormTable<ExamplePet> {

    public ExamplePetTable(BormAPI bormAPI) {
        super(bormAPI, ExamplePet.class, "example_pets", true);
    }

}
```

A single foreign reference:

```java
public class ExampleUser extends BormObject {

    @Column(primaryKey = true)
    private UUID id;

    @Column
    private long creationDate;

    @Column(foreignTable = "example_pets", cascadeDelete = true)
    private ExamplePet favoritePet;

    // ... constructors as before, now also accepting an ExamplePet

}
```

Or a `List`/`Map` of foreign references - BORM resolves every id in the collection in a single query rather than one query per entry:

```java
import java.util.List;

public class ExampleUser extends BormObject {

    @Column(primaryKey = true)
    private UUID id;

    @Column
    private long creationDate;

    @Column(foreignTable = "example_pets", cascadeDelete = true)
    private List<ExamplePet> pets;

    // ... constructors as before, now also accepting a List<ExamplePet>

}
```

With `cascadeDelete = true`, deleting the `ExampleUser` also deletes its `favoritePet`/`pets` from `example_pets`.

### Auto-increment keys

Mark an `int`, `Integer`, `long` or `Long` primary key with `autoIncrement = true` and leave it at `0`. The database generates the value on the first save and BORM writes it back to the object:

```java
@Column(primaryKey = true, autoIncrement = true)
private int id;
```

`table.add(object)` saves the object first and caches it under its generated id once the save completes. `table.add(object, false)` can't cache an object that has no id yet, so it logs a message and does nothing.

### Saving/deleting everything, and loading by another field

```java
// Persist every currently-loaded object in the table in one batch
exampleTable.saveAll(unused -> System.out.println("Saved everyone!"));

// Delete every row in the table and clear the in-memory cache
exampleTable.deleteAll();

// Load data by a column other than the primary key
exampleTable.loadDataByField("creationDate", System.currentTimeMillis(), () -> System.out.println("Loaded!"));

// Re-read one row and replace the cached object with the fresh copy
exampleTable.refresh(playerId, () -> System.out.println("Refreshed!"));
```

### Querying beyond a primary key or a single equality match

`table.query()` gives you a small filter/sort/limit builder for anything `loadDataByField` doesn't cover - no joins across tables, since BORM never joins (foreign keys are always resolved as separate queries):

```java
import net.bitbylogic.orm.query.QueryOperator;

exampleTable.query()
        .where("creationDate", QueryOperator.GREATER_THAN, cutoffTimestamp)
        .orderBy("creationDate", false) // false = descending
        .limit(10)
        .execute(users -> {
            // users: the 10 most recently created accounts, in that order
        });
```

Query results are **not** added to the table's cache - a one-off scan over many players shouldn't pin all of them in memory. If you want a specific result kept around, call `exampleTable.add(user, false)` yourself.

### Grouping multiple writes into one atomic transaction

`save()`/`delete()` each use their own connection by default. When several writes need to succeed or fail together - transferring currency between two players, for example - use `bormAPI.transaction(...)` instead, which shares one connection across every call made against it and only commits if the whole block completes without throwing:

```java
bormAPI.transaction(tx -> {
    try {
        senderAccount.setBalance(senderAccount.getBalance() - amount);
        receiverAccount.setBalance(receiverAccount.getBalance() + amount);

        accountTable.save(senderAccount, tx);
        accountTable.save(receiverAccount, tx);
    } catch (SQLException e) {
        throw new RuntimeException(e);
    }
}, success -> {
    if (!success) {
        System.out.println("Transfer failed, both sides rolled back.");
    }
});
```

If anything inside the block throws, every write made through that `tx` rolls back together - including cascaded deletes, since `delete(object, tx)` threads the same transaction through its cascade. A delete that is rolled back also puts the object back into the table's cache.

The `onDataUpdated`/`onDataDeleted` hooks and Redis notifications for transactional writes only run after the transaction commits. You can add your own with `tx.addAfterCommitHook(...)` (runs on commit) and `tx.addRollbackHook(...)` (runs on rollback).

### Reacting to data changes

`BormTable` exposes hooks you can override to react to data being loaded, added, updated or deleted:

```java
import net.bitbylogic.orm.BormAPI;
import net.bitbylogic.orm.data.BormTable;

public class ExampleTable extends BormTable<ExampleUser> {

    public ExampleTable(BormAPI bormAPI) {
        super(bormAPI, ExampleUser.class, "example_users", true);
    }

    @Override
    public void onDataLoaded() {
        // Called once every row has finished loading from the database
    }

    @Override
    public void onDataAdded(ExampleUser user) {
        // Called whenever a user is added to the table's cache, via add() or after loading
    }

    @Override
    public void onDataUpdated(ExampleUser user) {
        // Called after a successful save()
    }

    @Override
    public void onDataDeleted(ExampleUser user) {
        // Called after a successful delete()
    }

}
```

## Syncing data across servers with Redis

If multiple servers share the same database, BORM can broadcast save/delete/save-all events over Redis (via [Bit's RPS](https://github.com/BitByLogics/Bits-RPS)), so every server's in-memory cache stays in sync - no extra code needed at the call site once it's wired up. Bit's RPS must be on your classpath for this.

```java
import net.bitbylogic.orm.BormAPI;
import net.bitbylogic.orm.redis.BormRedisHook;
import net.bitbylogic.rps.client.RedisClient;

public class Example {

    private final BormAPI bormAPI;

    public Example() {
        this.bormAPI = new BormAPI(new File("database.sql"));

        // Set up your RedisManager/RedisClient per Bit's RPS: https://github.com/BitByLogics/Bits-RPS
        RedisClient redisClient = ...;

        bormAPI.setRedisHook(new BormRedisHook(bormAPI, redisClient));
    }

}
```

Once the hook is set, `save()`, `saveAll()` and `delete()` automatically publish a change event, and other servers listening on the same Redis instance will refresh the affected row (or the whole table, for `saveAll()`) in their own cache. A refresh swaps the cached object for a fresh copy, so don't hold on to old references across a sync. Servers using SQLite ignore these events.

# Column Annotation Attributes

| Attribute Name | Attribute Object | Default Value | Description                                                                                                                                                        |
|----------------|------------------|---------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| dataType       | String           | ""            | This allows you to define the data type of the field, for example `VARCHAR(36)`. If left empty, it will be inferred based on the fields type.                      |
| name           | String           | Field's Name  | This is the name that the column will be called, by default will be the fields name.                                                                               |
| allowNull      | Boolean          | false         | Determines whether or not the column can be `NULL` in the table.                                                                                                   |
| autoIncrement  | Boolean          | false         | Used for int/Integer/long/Long primary keys, will set the field as a unique number generated by SQL.                                                               |
| primaryKey     | Boolean          | false         | Marks this field as the objects primary key, this is what's used to retrieve the object from the table. Only one field can be marked as primaryKey per BormObject. |
| updateOnSave   | Boolean          | true          | Marks this field for being saved when the object is saved to the database.                                                                                         |
| subClass       | Boolean          | false         | Flattens this field's own `@Column`-annotated fields into the parent object's table (recursively) instead of giving this field its own single column. Intended for simple embedded value objects, not entities with their own identity/table. |
| foreignTable   | String           | ""            | This is the foreign table name of fields BormObject. Used to retrieve the BormTable and thus fetch the BormObject from it.                                         |
| cascadeDelete  | Boolean          | false         | Determines whether the foreign object linked to this field should be deleted from its table when this object is deleted.                                           |

# Data types

When `dataType` is empty, BORM infers it from the field's Java type: `String` and unknown types become `TEXT`, `int` `INT`, `long` `BIGINT`, `double` `DOUBLE`, `float` `FLOAT`, `short` `SMALLINT`, `byte` `TINYINT`, `byte[]` `LONGBLOB`, `boolean` `BOOLEAN`, `char` `CHAR` and `UUID` `VARCHAR(36)`. Lists and maps of foreign objects are stored as `LONGTEXT`.

MySQL cannot index a `TEXT` column, so on MySQL a primary key whose type was only inferred (a `String`) is created as `VARCHAR(255)` instead. Set `dataType` yourself to use another length. SQLite always uses `TEXT`.

# Registering Field Processors For Complex Objects

For complex objects, you can create a FieldProcessor that allows you to process the object into an object and load it from an object!

Let's say you have this ComplexObject and want to add it as a column in the table.

```java
public class ComplexObject {

    private final String name;
    private final int favoriteNumber;
    private final String favoriteColor;

    public ComplexObject(String name, int favoriteNumber, String favoriteColor) {
        this.name = name;
        this.favoriteNumber = favoriteNumber;
        this.favoriteColor = favoriteColor;
    }

    public String getName() {
        return name;
    }

    public int getFavoriteNumber() {
        return favoriteNumber;
    }

    public String getFavoriteColor() {
        return favoriteColor;
    }

}
```

This object cannot be automatically stored in the table and has to be processed somehow, that's where the FieldProcessor interface comes in!
You can create a processor by implementing the FieldProcessor interface and subsequent methods.

```java
public class ExampleFP implements FieldProcessor<ComplexObject> {

    @Override
    public Object processTo(ComplexObject object) {
        return String.format("%s:%s:%s", object.getName(), object.getFavoriteNumber(), object.getFavoriteColor());
    }

    @Override
    public ComplexObject processFrom(Object object) {
        String[] data = ((String) object).split(":");
        return new ComplexObject(data[0], Integer.parseInt(data[1]), data[2]);
    }

}
```

In this example, we just parse the object to a string and back from a string! You then need to register your processor to the BormAPI instance **before** you register the tables that use it.

```java
import net.bitbylogic.orm.BormAPI;
import net.bitbylogic.utils.reflection.TypeToken;

public class Example {

    private final BormAPI bormAPI;

    private ExampleTable exampleTable;

    public Example() {
        this.bormAPI = new BormAPI(new File("database.sql"));

        bormAPI.registerFieldProcessor(new TypeToken<>() {}, new ExampleFP());

        bormAPI.registerTable(ExampleTable.class, exampleTable -> this.exampleTable = exampleTable);
    }

    public static void main(String[] args) {
        new Example();
    }

}
```

Now any fields that are marked with the Column annotation and are of the type ComplexObject will be processed with the ExampleFP field processor!

Only the first processor registered for a type is used. If a processor throws while reading a row, that row is skipped and the error is logged, the rest of the table still loads.

BORM ships processors for `List<String>` (registered by default) and for Bukkit `Location` (`BukkitLocationProcessor`, which you register yourself). The location processor stores the world name and **block** coordinates only, so decimals, yaw and pitch are not kept, and a world that isn't loaded yields a location without a world.

# Notes

* BORM does not migrate schemas. Tables are created with `CREATE TABLE IF NOT EXISTS`, so adding a field to a class that already has a table requires you to alter the table yourself.
* The public collections BORM hands out (`getDataMap()`, `getColumnData()`, `getTables()`) are read-only views.
* Failures in the background are logged through the `BORM` logger.

# Upgrading from 1.x

2.0.0 changes a few signatures. The main ones:

* `getDataMap()`, `getColumnData()` and `getTables()` are read-only views. Use `add`, `unload`, `unloadAll` instead of mutating them. `getFieldProcessors()` and `getPendingTables()` were removed.
* `executeStatement`, `executeQuery`, `executeBatch` and `transaction` return a `CompletableFuture` instead of `void`.
* `BormAPI(File)` throws `IllegalStateException` when the database file can't be created, instead of returning a half-built instance.
* BORM's own `TypeToken` was removed, use `net.bitbylogic.utils.reflection.TypeToken` from Bit's Utils.
* Table names must be plain identifiers (letters, digits, underscores).
* The three-argument `BormTable` constructor no longer loads every row. Pass `true` as the fourth constructor argument (`loadData`) to preload every row, for example if you read `getDataMap()` right after startup.
* The dependency moved from JitPack to the Minebit repository: `net.bitbylogic:bits-orm`.

See the [CHANGELOG](CHANGELOG.md) for everything else.

# Building

```bash
mvn clean verify
```

This compiles, runs the tests, builds the sources and javadoc jars, writes a JaCoCo report to `target/site/jacoco` and fails if SpotBugs finds anything at Medium or above.

The tests use SQLite and need nothing else. A set of MySQL tests is skipped unless you point them at a server:

```bash
docker run -d --name borm-mysql-test -e MYSQL_ROOT_PASSWORD=borm-test -e MYSQL_DATABASE=borm -p 127.0.0.1:3307:3306 mysql:8.4
mvn clean verify -Dborm.mysql.port=3307
docker rm -f borm-mysql-test
```

# Releasing

Releases are published to `https://repo.minebit.dev/releases` and are immutable, a version can never be reused. To release, set the version in `pom.xml` to `X.Y.Z`, add its entry to the CHANGELOG, then push a tag named exactly `X.Y.Z`. The Release workflow checks that the tag matches the pom version, deploys, and creates the GitHub release.

# License

MIT, see [LICENSE](LICENSE).
