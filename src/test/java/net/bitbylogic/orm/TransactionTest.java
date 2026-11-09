package net.bitbylogic.orm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TransactionTest {

    private File dbFile;
    private BormAPI bormAPI;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        dbFile = tempDir.resolve("borm-transaction-test.db").toFile();
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

    private Optional<TestObject> load(TestTable table, String id) {
        return Await.<Optional<TestObject>>value(cb -> table.getDataFromDB(id, true, true, cb));
    }

    @Test
    void bothSavesInATransactionCommitTogether() {
        TestTable table = registerTable();

        boolean success = Await.<Boolean>value(cb -> bormAPI.transaction(tx -> {
            try {
                table.save(new TestObject("tx-a", "Alpha", null), tx);
                table.save(new TestObject("tx-b", "Beta", null), tx);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, cb));

        assertTrue(success);

        reopen();
        TestTable reloaded = registerTable();

        assertTrue(load(reloaded, "tx-a").isPresent());
        assertTrue(load(reloaded, "tx-b").isPresent());
    }

    @Test
    void anExceptionMidTransactionRollsBackEverything() {
        TestTable table = registerTable();

        boolean success = Await.<Boolean>value(cb -> bormAPI.transaction(tx -> {
            try {
                table.save(new TestObject("tx-c", "Gamma", null), tx);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            throw new RuntimeException("simulated failure after the first save");
        }, cb));

        assertFalse(success);

        reopen();
        TestTable reloaded = registerTable();

        assertTrue(load(reloaded, "tx-c").isEmpty(),
                "a save preceding a later failure in the same transaction must not persist");
    }

}
