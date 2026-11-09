package net.bitbylogic.orm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BormTableTest {

    private File dbFile;
    private BormAPI bormAPI;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        dbFile = tempDir.resolve("borm-test.db").toFile();
        bormAPI = new BormAPI(dbFile);
    }

    @AfterEach
    void tearDown() {
        bormAPI.close();
    }

    private TestTable registerTestTable() {
        return Await.<TestTable>value(cb -> bormAPI.registerTable(TestTable.class, cb));
    }

    private void reopen() {
        bormAPI.close();
        bormAPI = new BormAPI(dbFile);
    }

    private void save(TestTable table, TestObject object) {
        Await.run(cb -> table.save(object, v -> cb.run()));
    }

    private Optional<TestObject> load(TestTable table, String id) {
        return Await.<Optional<TestObject>>value(cb -> table.getDataFromDB(id, true, true, cb));
    }

    @Test
    void saveAndReloadFromDisk() {
        TestTable table = registerTestTable();

        save(table, new TestObject("row-1", "Alpha", null));

        reopen();
        TestTable reloaded = registerTestTable();

        Optional<TestObject> found = load(reloaded, "row-1");
        assertTrue(found.isPresent());
        assertEquals("Alpha", found.get().getName());
    }

    @Test
    void nullFieldRoundTrips() {
        TestTable table = registerTestTable();

        save(table, new TestObject("row-null", "NoPayload", null));

        reopen();
        TestTable reloaded = registerTestTable();

        Optional<TestObject> found = load(reloaded, "row-null");
        assertTrue(found.isPresent());
        assertNull(found.get().getPayload());
    }

    @Test
    void byteArrayRoundTripsExactly() {
        TestTable table = registerTestTable();

        byte[] payload = {0, 1, 2, '\'', -1, -128, 127, 'a', 'b'};
        save(table, new TestObject("row-bytes", "WithBytes", payload));

        reopen();
        TestTable reloaded = registerTestTable();

        Optional<TestObject> found = load(reloaded, "row-bytes");
        assertTrue(found.isPresent());
        assertArrayEquals(payload, found.get().getPayload());
    }

    @Test
    void savingSameRowTwiceUpdatesInPlaceInsteadOfReinserting() {
        TestTable table = registerTestTable();

        save(table, new TestObject("row-update", "First", null));
        long rowIdBefore = rowIdOf("row-update");

        save(table, new TestObject("row-update", "Second", null));
        long rowIdAfter = rowIdOf("row-update");

        assertEquals(rowIdBefore, rowIdAfter,
                "save() on an existing row must UPDATE in place, not DELETE+INSERT (rowid must not change)");

        reopen();
        TestTable reloaded = registerTestTable();

        Optional<TestObject> found = load(reloaded, "row-update");
        assertTrue(found.isPresent());
        assertEquals("Second", found.get().getName());
        assertEquals(1, countRows());
    }

    @Test
    void saveAllPersistsEveryObjectInOneBatch() {
        TestTable table = registerTestTable();

        table.add(new TestObject("batch-1", "One", null), false);
        table.add(new TestObject("batch-2", "Two", null), false);
        table.add(new TestObject("batch-3", "Three", null), false);

        Await.run(cb -> table.saveAll(v -> cb.run()));

        reopen();
        registerTestTable();

        assertEquals(3, countRows());
    }

    @Test
    void deleteRemovesRowFromDatabase() {
        TestTable table = registerTestTable();

        TestObject object = new TestObject("row-delete", "ToDelete", null);
        table.add(object);
        Await.run(cb -> bormAPI.executeQuery("SELECT 1;", rs -> cb.run()));

        table.delete(object);

        Await.run(cb -> bormAPI.executeQuery("SELECT 1;", rs -> cb.run()));

        reopen();
        TestTable reloaded = registerTestTable();

        Optional<TestObject> found = load(reloaded, "row-delete");
        assertTrue(found.isEmpty());
    }

    private long rowIdOf(String id) {
        return Await.<Long>value(cb -> bormAPI.executeQuery("SELECT rowid FROM test_object WHERE id = ?;", rs -> {
            try {
                if (rs != null && rs.next()) {
                    cb.accept(rs.getLong(1));
                } else {
                    cb.accept(-1L);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, id));
    }

    private int countRows() {
        return Await.<Integer>value(cb -> bormAPI.executeQuery("SELECT COUNT(*) FROM test_object;", rs -> {
            try {
                rs.next();
                cb.accept(rs.getInt(1));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));
    }

}
