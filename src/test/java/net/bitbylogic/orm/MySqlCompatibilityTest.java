package net.bitbylogic.orm;

import net.bitbylogic.orm.data.BormObject;
import net.bitbylogic.orm.data.BormTable;
import net.bitbylogic.orm.query.QueryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs against a real MySQL server, only when -Dborm.mysql.port is set, e.g. against a throwaway
 * Docker container: docker run -d -e MYSQL_ROOT_PASSWORD=borm-test -e MYSQL_DATABASE=borm -p 127.0.0.1:3307:3306 mysql:8.4
 */
class MySqlCompatibilityTest {

    private static final String[] TABLES = {
            "test_object", "auto_id_object", "boxed_auto_id_object", "child_object", "parent_list_object"
    };

    private BormAPI bormAPI;

    @BeforeEach
    void setUp() throws Exception {
        String port = System.getProperty("borm.mysql.port");
        assumeTrue(port != null && !port.isBlank(), "borm.mysql.port is not set, skipping MySQL tests");

        bormAPI = connect(port);

        for (String table : TABLES) {
            bormAPI.executeStatement("DROP TABLE IF EXISTS " + table + ";").get(10, TimeUnit.SECONDS);
        }
    }

    @AfterEach
    void tearDown() {
        if (bormAPI != null) {
            bormAPI.close();
        }
    }

    private BormAPI connect(String port) {
        String password = System.getProperty("borm.mysql.password", "borm-test");
        return new BormAPI("127.0.0.1", "borm", port, "root", password);
    }

    private void reconnect() {
        bormAPI.close();
        bormAPI = connect(System.getProperty("borm.mysql.port"));
    }

    private void awaitBarrier() {
        for (String table : TABLES) {
            try {
                bormAPI.executeOrderedStatement(table, "DO 0;", null).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                fail(e);
            }
        }
    }

    private <O extends BormObject, T extends BormTable<O>> T register(Class<T> tableClass) {
        return Await.<T>value(cb -> bormAPI.registerTable(tableClass, cb));
    }

    private int countRows(String table) {
        AtomicInteger count = new AtomicInteger(-1);

        Await.run(cb -> bormAPI.executeQueryAfterWrites(table, "SELECT COUNT(*) FROM " + table + ";", rs -> {
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
    void savedObjectsSurviveAReconnectAndUpdateInPlace() {
        TestTable table = register(TestTable.class);

        table.add(new TestObject("m-1", "First", new byte[]{1, 2, 3}));
        awaitBarrier();

        table.save(new TestObject("m-1", "Renamed", null));
        awaitBarrier();

        reconnect();
        TestTable reloaded = register(TestTable.class);
        Optional<TestObject> found = Await.<Optional<TestObject>>value(cb -> reloaded.getDataFromDB("m-1", true, true, cb));

        assertTrue(found.isPresent());
        assertEquals("Renamed", found.get().getName());
        assertEquals(1, countRows("test_object"));
    }

    @Test
    void autoIncrementKeysAreGeneratedWrittenBackAndKeptOnResave() {
        AutoIdTable table = register(AutoIdTable.class);
        AutoIdObject first = new AutoIdObject(0, "First");
        AutoIdObject second = new AutoIdObject(0, "Second");

        table.add(first);
        Await.until(() -> first.getId() > 0, "the first object never received its generated key");
        table.add(second);
        Await.until(() -> second.getId() > 0, "the second object never received its generated key");

        assertNotEquals(first.getId(), second.getId());
        assertSame(first, table.getDataMap().get(first.getId()));

        int firstId = first.getId();
        Await.run(cb -> table.save(first, saved -> cb.run()));

        assertEquals(firstId, first.getId());
        assertEquals(2, countRows("auto_id_object"));
    }

    @Test
    void aBoxedLongAutoIncrementKeyIsWrittenBack() {
        BoxedAutoIdTable table = register(BoxedAutoIdTable.class);
        BoxedAutoIdObject object = new BoxedAutoIdObject(0L, "Boxed");

        table.add(object);

        Await.until(() -> object.getId() > 0, "the boxed key never received its generated value");
    }

    @Test
    void transactionsCommitTogetherAndRollBackTogether() throws Exception {
        AutoIdTable table = register(AutoIdTable.class);
        AutoIdObject kept = new AutoIdObject(0, "Kept");
        AutoIdObject discarded = new AutoIdObject(0, "Discarded");

        boolean committed = bormAPI.transaction(tx -> {
            try {
                table.save(kept, tx);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).get(10, TimeUnit.SECONDS);

        boolean rolledBack = bormAPI.transaction(tx -> {
            try {
                table.save(discarded, tx);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            throw new RuntimeException("simulated failure");
        }).get(10, TimeUnit.SECONDS);

        assertTrue(committed);
        assertFalse(rolledBack);
        assertTrue(kept.getId() > 0);
        assertEquals(1, countRows("auto_id_object"));
    }

    @Test
    void writesToOneTableStayOrderedOnTheMultiThreadedPool() throws Exception {
        TestTable table = register(TestTable.class);

        for (int i = 0; i < 100; i++) {
            TestObject object = new TestObject("ordered-" + i, "Ordered", null);

            table.add(object);
            table.delete(object);
        }

        bormAPI.executeOrderedStatement(table.getTable(), "DELETE FROM test_object WHERE 1 = 0;", null).get(30, TimeUnit.SECONDS);

        assertEquals(0, countRows("test_object"));
    }

    @Test
    void foreignListsRoundTripInOrderAndCascadeDeletes() {
        ChildTable childTable = register(ChildTable.class);
        ParentListTable parentTable = register(ParentListTable.class);

        ChildObject a = new ChildObject("mc-a", "A");
        ChildObject b = new ChildObject("mc-b", "B");
        ChildObject c = new ChildObject("mc-c", "C");

        childTable.add(a);
        childTable.add(b);
        childTable.add(c);
        ParentListObject parent = new ParentListObject("mp-1", List.of(c, a, b));
        parentTable.add(parent);
        awaitBarrier();

        reconnect();
        ChildTable reloadedChildren = register(ChildTable.class);
        ParentListTable reloadedParents = register(ParentListTable.class);

        ParentListObject loaded = Await.<Optional<ParentListObject>>value(cb ->
                reloadedParents.getDataFromDB("mp-1", true, true, cb)).orElseThrow();

        assertEquals(List.of("C", "A", "B"), loaded.getChildren().stream().map(ChildObject::getLabel).toList());

        reloadedParents.delete(loaded);
        awaitBarrier();

        assertEquals(0, countRows("parent_list_object"));
        assertEquals(0, countRows("child_object"));
        assertNotNull(reloadedChildren);
    }

    @Test
    void queriesFilterSortAndLimit() {
        TestTable table = register(TestTable.class);

        table.add(new TestObject("q-1", "Charlie", null));
        table.add(new TestObject("q-2", "Alpha", null));
        table.add(new TestObject("q-3", "Bravo", null));
        awaitBarrier();

        List<TestObject> results = Await.<List<TestObject>>value(cb ->
                table.query().where("name", QueryOperator.NOT_EQUALS, "Charlie").orderBy("name", true).limit(5).execute(cb));

        assertEquals(List.of("Alpha", "Bravo"), results.stream().map(TestObject::getName).toList());
    }

    @Test
    void saveAllWritesTheWholeCacheAsOneBatch() {
        TestTable table = register(TestTable.class);

        table.add(new TestObject("b-1", "One", null), false);
        table.add(new TestObject("b-2", "Two", null), false);

        Await.run(cb -> table.saveAll(unused -> cb.run()));

        assertEquals(2, countRows("test_object"));
    }

}
