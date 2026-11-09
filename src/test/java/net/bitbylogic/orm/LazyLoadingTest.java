package net.bitbylogic.orm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LazyLoadingTest {

    private File dbFile;
    private BormAPI bormAPI;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        dbFile = tempDir.resolve("borm-lazy-test.db").toFile();
        bormAPI = new BormAPI(dbFile);
    }

    @AfterEach
    void tearDown() {
        bormAPI.close();
    }

    private void reopen() {
        bormAPI.close();
        bormAPI = new BormAPI(dbFile);
    }

    private TestTable registerTable() {
        return Await.<TestTable>value(cb -> bormAPI.registerTable(TestTable.class, cb));
    }

    @Test
    void registeringATableWithExistingRowsDoesNotEagerlyLoadThem() {
        TestTable table = registerTable();
        Await.run(cb -> table.save(new TestObject("row-1", "Alpha", null), v -> cb.run()));

        reopen();
        TestTable reloaded = registerTable();

        assertTrue(reloaded.getDataMap().isEmpty(),
                "the default 3-arg constructor must not eagerly load existing rows");
    }

    @Test
    void getDataFromDbStillLoadsAndCachesOnDemand() {
        TestTable table = registerTable();
        Await.run(cb -> table.save(new TestObject("row-2", "Beta", null), v -> cb.run()));

        reopen();
        TestTable reloaded = registerTable();
        assertTrue(reloaded.getDataMap().isEmpty());

        Optional<TestObject> found = Await.<Optional<TestObject>>value(
                cb -> reloaded.getDataFromDB("row-2", true, true, cb));

        assertTrue(found.isPresent());
        assertTrue(reloaded.getDataMap().containsKey("row-2"),
                "a successful on-demand fetch should still populate the cache");
    }

    @Test
    void unloadEvictsFromCacheWithoutTouchingTheDatabase() {
        TestTable table = registerTable();
        TestObject object = new TestObject("row-3", "Gamma", null);
        Await.run(cb -> table.save(object, v -> cb.run()));

        Await.<Optional<TestObject>>value(cb -> table.getDataFromDB("row-3", true, true, cb));
        assertTrue(table.getDataMap().containsKey("row-3"));

        table.unload(object);
        assertFalse(table.getDataMap().containsKey("row-3"), "unload() must remove the entry from the cache");

        reopen();
        TestTable reloaded = registerTable();
        Optional<TestObject> stillOnDisk = Await.<Optional<TestObject>>value(
                cb -> reloaded.getDataFromDB("row-3", true, true, cb));

        assertTrue(stillOnDisk.isPresent(), "unload() must not delete the row from the database");
    }

}
