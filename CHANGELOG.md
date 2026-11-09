# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

Planned as 2.0.0, the first release published as `net.bitbylogic:bits-orm` on the Minebit repository.

### Breaking changes
* The jar no longer shades its dependencies. It contains only BORM's classes (about 75 KB instead of 19 MB), and HikariCP, Bit's Utils and the JDBC drivers are normal transitive dependencies. Shade and relocate them in your own plugin build, see the README.
* `BormTable#getDataMap()`, `BormStatements#getColumnData()` and `BormAPI#getTables()` return read-only views. Use `add`, `unload` and the new `unloadAll` to change the cache.
* `BormAPI#getFieldProcessors()` and `BormAPI#getPendingTables()` were removed.
* `executeStatement`, `executeQuery`, `executeBatch` and `transaction` return a `CompletableFuture` instead of `void`.
* `BormAPI(File)` throws `IllegalStateException` if the database file or its folder can't be created, instead of returning an instance with no data source.
* Table names and column names must be plain identifiers (letters, digits, underscores). An invalid table name throws `IllegalArgumentException`, an invalid column name is skipped with a warning.
* BORM's own `TypeToken` class was removed. Use `net.bitbylogic.utils.reflection.TypeToken` from Bit's Utils.
* `getDataSaveStatement` returns the SQL together with its bound values, and all statements are parameterized.
* Dependencies come from the Minebit repository (`net.bitbylogic:bits-utils-common`, `net.bitbylogic:bits-rps`) instead of JitPack.

### Added
* `Transaction` and `BormAPI#transaction` for atomic multi-write transactions, with after-commit and rollback hooks.
* `BormTable#query()`, a filter/sort/limit query builder, and `QueryOperator`.
* `BormTable#unload`, `unloadAll`, `refresh` and `deleteAll`.
* Lazy loading: the three-argument `BormTable` constructor no longer loads every row.
* `onDataUpdated` hook on `BormTable`.
* Per-table write ordering (`executeOrderedStatement`, `executeOrderedBatch`) and reads that wait for earlier writes to the same table (`executeQueryAfterWrites`).
* Auto-increment keys of type `int`, `Integer`, `long` and `Long`, written back to the object after the first save.
* `byte[]` columns (`LONGBLOB`).
* Redis `SAVE_ALL` events, sent by `saveAll()` and applied by reloading the table.
* Sources and javadoc jars, a JaCoCo report and a SpotBugs gate in the build.
* README, CHANGELOG, LICENSE, SECURITY and CONTRIBUTING documents.

### Changed
* Moved from the Spigot to the Paper API.
* A `String` primary key with no explicit `dataType` is created as `VARCHAR(255)` on MySQL, because MySQL can't index `TEXT`.
* The database thread pool is shut down before the connection pool on `close()`, so queued writes finish. The SQLite thread is a daemon thread.
* A reload (`loadData`, `SAVE_ALL` from Redis) swaps the cache in one step instead of clearing it first, and a Redis `SAVE` replaces the cached object without a gap.
* After-commit `onDataUpdated` / `onDataDeleted` for transactional writes, so they never fire for a write that rolled back, and a rolled-back delete puts the object back in the cache.
* Failures are logged through the `BORM` logger with their stack trace instead of `printStackTrace`.
* Unreadable values from a field processor skip that row instead of aborting the whole load.

### Fixed
* `List` and `Map` foreign fields whose element type is a subclass of `BormObject` were skipped, which broke loading of the whole table.
* Loading a row whose foreign object no longer exists hung table registration forever. It now loads the field as `null`.
* A table that needed several foreign tables only waited for the first one, and only wired up the last one that arrived.
* Registering the same table twice at the same time could register it twice.
* Queued writes were lost on `close()`.
* Writes to one table could reach MySQL out of order, for example a delete running before the save it followed.
* Query results could come back out of `ORDER BY` order when foreign data loaded asynchronously.
* Auto-increment primary keys: SQLite rejected the generated `CREATE TABLE`, new objects were inserted with id `0` and cached under it, and re-saving an object could overwrite its id with the last inserted row id.
* MySQL could not create tables with a `String` primary key and no explicit `dataType`.
* `saveAll()` with an empty cache and no callback still sent a Redis message.
* A transaction hook that threw made a committed transaction report failure.
* `StringListProcessor` failed on a `NULL` column.

## [1.2.0]

Earlier releases are not recorded in this file. See the Git history.
