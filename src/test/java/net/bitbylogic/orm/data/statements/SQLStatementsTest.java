package net.bitbylogic.orm.data.statements;

import net.bitbylogic.orm.BormAPI;
import net.bitbylogic.orm.TestObject;
import net.bitbylogic.utils.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SQLStatementsTest {

    private BormAPI bormAPI;
    private SQLStatements<TestObject> statements;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        bormAPI = new BormAPI(tempDir.resolve("borm-statements-test.db").toFile());
        statements = new SQLStatements<>(bormAPI, "test_object");
        statements.loadColumnData(new TestObject(), new ArrayList<>());
    }

    @AfterEach
    void tearDown() {
        bormAPI.close();
    }

    @Test
    void createStatementDeclaresColumnsAndPrimaryKey() {
        String sql = statements.getTableCreateStatement();

        assertTrue(sql.startsWith("CREATE TABLE IF NOT EXISTS test_object ("));
        assertTrue(sql.contains("id VARCHAR(255) NOT NULL"), "an inferred String key must not be TEXT, MySQL can't index it");
        assertTrue(sql.contains("name TEXT NOT NULL"));
        assertTrue(sql.contains("payload LONGBLOB"));
        assertTrue(sql.contains("PRIMARY KEY(id)"));
    }

    @Test
    void saveStatementUsesMySqlUpsertWithBoundValues() {
        Pair<String, List<Object>> save = statements.getDataSaveStatement(new TestObject("s-1", "Name", null));

        assertEquals("INSERT INTO test_object (id, name, payload) VALUES(?, ?, ?) ON DUPLICATE KEY UPDATE name=VALUES(name), payload=VALUES(payload);", save.getKey());
        assertEquals(3, save.getValue().size());
        assertEquals("s-1", save.getValue().get(0));
        assertEquals("Name", save.getValue().get(1));
        assertNull(save.getValue().get(2));
    }

    @Test
    void saveStatementCanBeLimitedToSomeFields() {
        Pair<String, List<Object>> save = statements.getDataSaveStatement(new TestObject("s-2", "Name", null), "name");

        assertEquals("INSERT INTO test_object (id, name) VALUES(?, ?) ON DUPLICATE KEY UPDATE name=VALUES(name);", save.getKey());
        assertEquals(List.of("s-2", "Name"), save.getValue());
    }

    @Test
    void deleteStatementTargetsThePrimaryKey() {
        Pair<String, List<Object>> delete = statements.getDataDeleteStatement(new TestObject("d-1", "Name", null));

        assertEquals("DELETE FROM test_object WHERE id=?;", delete.getKey());
        assertEquals(List.of("d-1"), delete.getValue());
    }

    @Test
    void updateStatementSetsEveryNonKeyColumn() {
        Pair<String, List<Object>> update = statements.getUpdateStatement(new TestObject("u-1", "Name", null));

        assertEquals("UPDATE test_object SET name=?, payload=? WHERE id = ?;", update.getKey());
        assertEquals(3, update.getValue().size());
        assertEquals("u-1", update.getValue().get(2));
    }

    @Test
    void clearStatementDeletesEveryRow() {
        assertEquals("DELETE FROM test_object;", statements.getTableClearStatement());
    }

    @Test
    void columnDataCannotBeModifiedFromOutside() {
        assertThrows(UnsupportedOperationException.class, () -> statements.getColumnData().clear());
        assertTrue(statements.getColumnData("NAME").isPresent());
        assertEquals("id", statements.getPrimaryKeyData().getName());
    }

}
