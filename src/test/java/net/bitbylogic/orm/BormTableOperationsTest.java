package net.bitbylogic.orm;

import net.bitbylogic.orm.data.BormObject;
import net.bitbylogic.orm.data.BormTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BormTableOperationsTest {

    private File dbFile;
    private BormAPI bormAPI;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        dbFile = tempDir.resolve("borm-operations-test.db").toFile();
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

    private void awaitBarrier() {
        Await.run(cb -> bormAPI.executeQuery("SELECT 1;", rs -> cb.run()));
    }

    private <O extends BormObject, T extends BormTable<O>> T register(Class<T> tableClass) {
        return Await.<T>value(cb -> bormAPI.registerTable(tableClass, cb));
    }

    private int countRows(String table) {
        AtomicInteger count = new AtomicInteger(-1);

        Await.run(cb -> bormAPI.executeQuery("SELECT COUNT(*) FROM " + table + ";", rs -> {
            try {
                rs.next();
                count.set(rs.getInt(1));
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                cb.run();
            }
        }));

        return count.get();
    }

    @Test
    void saveAllWritesEveryCachedObjectInOneBatch() {
        TestTable table = register(TestTable.class);

        table.add(new TestObject("b-1", "One", null), false);
        table.add(new TestObject("b-2", "Two", null), false);
        table.add(new TestObject("b-3", "Three", null), false);

        Await.run(cb -> table.saveAll(unused -> cb.run()));

        assertEquals(3, countRows("test_object"));
    }

    @Test
    void saveAllWithNothingCachedStillCompletes() {
        TestTable table = register(TestTable.class);

        Await.run(cb -> table.saveAll(unused -> cb.run()));

        assertEquals(0, countRows("test_object"));
    }

    @Test
    void deleteAllClearsTheCacheAndTheTable() {
        TestTable table = register(TestTable.class);

        table.add(new TestObject("c-1", "One", null));
        table.add(new TestObject("c-2", "Two", null));
        awaitBarrier();

        table.deleteAll();
        awaitBarrier();

        assertTrue(table.getDataMap().isEmpty());
        assertEquals(0, countRows("test_object"));
    }

    @Test
    void preloadedTablesFillTheirCacheOnRegistration() {
        TestTable table = register(TestTable.class);
        table.add(new TestObject("p-1", "Preloaded", null));
        awaitBarrier();

        reopen();

        PreloadedTable preloaded = register(PreloadedTable.class);

        assertEquals("Preloaded", preloaded.getDataMap().get("p-1").getName());
    }

    @Test
    void loadDataByFieldPullsOnlyMatchingRows() {
        TestTable table = register(TestTable.class);

        table.add(new TestObject("f-1", "Match", null));
        table.add(new TestObject("f-2", "Match", null));
        table.add(new TestObject("f-3", "Other", null));
        awaitBarrier();

        reopen();
        TestTable reloaded = register(TestTable.class);

        Await.run(cb -> reloaded.loadDataByField("name", "Match", cb));

        assertEquals(2, reloaded.getDataMap().size());
        assertFalse(reloaded.getDataMap().containsKey("f-3"));
    }

    @Test
    void loadDataByFieldWithAnUnknownFieldStillCompletes() {
        TestTable table = register(TestTable.class);

        Await.run(cb -> table.loadDataByField("nope", "x", cb));

        assertTrue(table.getDataMap().isEmpty());
    }

    @Test
    void refreshReplacesTheCachedObjectAndDropsRowsThatNoLongerExist() {
        TestTable table = register(TestTable.class);
        TestObject original = new TestObject("rf-1", "Before", null);

        table.add(original);
        awaitBarrier();

        bormAPI.executeStatement("UPDATE test_object SET name = 'After' WHERE id = 'rf-1';");
        Await.run(cb -> table.refresh("rf-1", cb));

        assertEquals("After", table.getDataMap().get("rf-1").getName());
        assertNotSame(original, table.getDataMap().get("rf-1"));

        bormAPI.executeStatement("DELETE FROM test_object WHERE id = 'rf-1';");
        Await.run(cb -> table.refresh("rf-1", cb));

        assertFalse(table.getDataMap().containsKey("rf-1"));
    }

    @Test
    void batchLookupReturnsCachedAndStoredObjectsByIdAndSkipsMissingOnes() {
        TestTable table = register(TestTable.class);

        table.add(new TestObject("g-1", "One", null));
        table.add(new TestObject("g-2", "Two", null));
        awaitBarrier();

        reopen();
        TestTable reloaded = register(TestTable.class);

        Map<Object, TestObject> found = Await.<Map<Object, TestObject>>value(cb ->
                reloaded.getDataFromDBBatch(List.of("g-1", "g-2", "g-missing"), true, true, cb));

        assertEquals(2, found.size());
        assertEquals("Two", found.get("g-2").getName());
        assertTrue(reloaded.getDataMap().containsKey("g-1"));
    }

    @Test
    void lookupWithoutCachingLeavesTheCacheEmpty() {
        TestTable table = register(TestTable.class);

        table.add(new TestObject("n-1", "One", null));
        awaitBarrier();

        reopen();
        TestTable reloaded = register(TestTable.class);

        Optional<TestObject> found = Await.<Optional<TestObject>>value(cb -> reloaded.getDataFromDB("n-1", false, false, cb));

        assertTrue(found.isPresent());
        assertTrue(reloaded.getDataMap().isEmpty());
    }

    @Test
    void anAutoIncrementKeyIsWrittenBackToTheObject() {
        AutoIdTable table = register(AutoIdTable.class);
        AutoIdObject first = new AutoIdObject(0, "First");
        AutoIdObject second = new AutoIdObject(0, "Second");

        table.add(first);
        Await.until(() -> first.getId() > 0, "the first object never received its generated key");

        table.add(second);
        Await.until(() -> second.getId() > 0, "the second object never received its generated key");

        assertNotEquals(first.getId(), second.getId());
        assertSame(first, table.getDataMap().get(first.getId()));
        assertSame(second, table.getDataMap().get(second.getId()));
        assertEquals(2, countRows("auto_id_object"));
    }

    @Test
    void aBoxedLongAutoIncrementKeyIsWrittenBackToTheObject() {
        BoxedAutoIdTable table = register(BoxedAutoIdTable.class);
        BoxedAutoIdObject object = new BoxedAutoIdObject(0L, "Boxed");

        table.add(object);

        Await.until(() -> object.getId() > 0, "the boxed key never received its generated value");
        assertSame(object, table.getDataMap().get(object.getId()));
    }

    @Test
    void aTransactionalSaveWritesBackTheGeneratedKeyAndKeepsExistingOnes() throws Exception {
        AutoIdTable table = register(AutoIdTable.class);
        AutoIdObject first = new AutoIdObject(0, "First");
        AutoIdObject second = new AutoIdObject(0, "Second");

        boolean committed = bormAPI.transaction(tx -> {
            try {
                table.save(first, tx);
                table.save(second, tx);
                table.save(first, tx);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertTrue(committed);
        assertTrue(first.getId() > 0);
        assertTrue(second.getId() > first.getId());
        assertEquals(2, countRows("auto_id_object"));
    }

    @Test
    void savingAnExistingAutoIncrementObjectKeepsItsOwnId() {
        AutoIdTable table = register(AutoIdTable.class);
        AutoIdObject first = new AutoIdObject(0, "First");
        AutoIdObject second = new AutoIdObject(0, "Second");

        table.add(first);
        Await.until(() -> first.getId() > 0, "the first object never received its generated key");
        table.add(second);
        Await.until(() -> second.getId() > 0, "the second object never received its generated key");

        int firstId = first.getId();
        Await.run(cb -> table.save(first, saved -> cb.run()));

        assertEquals(firstId, first.getId(), "re-saving must not pick up the last inserted row id");
        assertEquals(2, countRows("auto_id_object"));
    }

    @Test
    void objectsSaveAndDeleteThemselvesThroughTheirOwningTable() {
        TestTable table = register(TestTable.class);
        TestObject object = new TestObject("o-1", "Self", null);

        table.add(object, false);
        object.save();
        awaitBarrier();
        assertEquals(1, countRows("test_object"));

        object.delete();
        awaitBarrier();
        assertEquals(0, countRows("test_object"));
        assertFalse(table.getDataMap().containsKey("o-1"));

        new TestObject("loose", "No table", null).save();
    }

    @Test
    void addingAnObjectThatIsAlreadyCachedIsIgnored() {
        TestTable table = register(TestTable.class);
        TestObject first = new TestObject("dup", "First", null);

        table.add(first);
        table.add(new TestObject("dup", "Second", null));
        awaitBarrier();

        assertSame(first, table.getDataMap().get("dup"));
    }

    @Test
    void unloadAllEmptiesTheCache() {
        TestTable table = register(TestTable.class);

        table.add(new TestObject("u-1", "One", null), false);
        table.add(new TestObject("u-2", "Two", null), false);
        table.unloadAll();

        assertTrue(table.getDataMap().isEmpty());
    }

}
