package net.bitbylogic.orm.query;

import net.bitbylogic.orm.Await;
import net.bitbylogic.orm.BormAPI;
import net.bitbylogic.orm.TestObject;
import net.bitbylogic.orm.TestTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryTest {

    private BormAPI bormAPI;
    private TestTable table;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        File dbFile = tempDir.resolve("borm-query-test.db").toFile();
        bormAPI = new BormAPI(dbFile);
        table = Await.<TestTable>value(cb -> bormAPI.registerTable(TestTable.class, cb));

        Await.run(cb -> table.save(new TestObject("q-1", "Alpha", null), v -> cb.run()));
        Await.run(cb -> table.save(new TestObject("q-2", "Beta", null), v -> cb.run()));
        Await.run(cb -> table.save(new TestObject("q-3", "Gamma", null), v -> cb.run()));
    }

    @AfterEach
    void tearDown() {
        bormAPI.close();
    }

    private List<TestObject> run(Query<TestObject> query) {
        return Await.<List<TestObject>>value(query::execute);
    }

    @Test
    void whereFiltersByEquality() {
        List<TestObject> results = run(table.query().where("name", QueryOperator.EQUALS, "Beta"));

        assertEquals(1, results.size());
        assertEquals("q-2", results.get(0).getId());
    }

    @Test
    void orderByAscendingSortsResults() {
        List<TestObject> results = run(table.query().orderBy("name", true));

        assertEquals(List.of("Alpha", "Beta", "Gamma"), results.stream().map(TestObject::getName).toList());
    }

    @Test
    void orderByDescendingSortsResults() {
        List<TestObject> results = run(table.query().orderBy("name", false));

        assertEquals(List.of("Gamma", "Beta", "Alpha"), results.stream().map(TestObject::getName).toList());
    }

    @Test
    void limitCapsResultCount() {
        List<TestObject> results = run(table.query().orderBy("name", true).limit(2));

        assertEquals(2, results.size());
        assertEquals(List.of("Alpha", "Beta"), results.stream().map(TestObject::getName).toList());
    }

    @Test
    void combinedWhereOrderByAndLimit() {
        List<TestObject> results = run(table.query()
                .where("name", QueryOperator.NOT_EQUALS, "Beta")
                .orderBy("name", false)
                .limit(1));

        assertEquals(1, results.size());
        assertEquals("Gamma", results.get(0).getName());
    }

    @Test
    void unknownFieldNameFailsClearlyInsteadOfProducingBrokenSql() {
        assertThrows(IllegalArgumentException.class, () -> table.query().where("nope", QueryOperator.EQUALS, "x"));
    }

    @Test
    void queryResultsAreNotAddedToTheCache() {
        run(table.query().where("name", QueryOperator.EQUALS, "Alpha"));

        assertFalse(table.getDataMap().containsKey("q-1"), "ad-hoc query results should not pollute the cache");
    }

}
