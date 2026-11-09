package net.bitbylogic.orm;

import net.bitbylogic.orm.data.BormObject;
import net.bitbylogic.orm.data.BormTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class ReliabilityTest {

    private File dbFile;
    private BormAPI bormAPI;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        dbFile = tempDir.resolve("borm-reliability-test.db").toFile();
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

    @Test
    void aFailingStatementCompletesItsFutureExceptionally() {
        CompletableFuture<Void> future = bormAPI.executeStatement("INSERT INTO table_that_does_not_exist VALUES (1);");

        ExecutionException exception = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertNotNull(exception.getCause());
    }

    @Test
    void workQueuedBeforeCloseIsStillWritten() {
        TestTable table = register(TestTable.class);

        table.add(new TestObject("queued", "Queued", null));
        reopen();

        TestTable reloaded = register(TestTable.class);
        Optional<TestObject> found = Await.<Optional<TestObject>>value(cb -> reloaded.getDataFromDB("queued", true, true, cb));

        assertTrue(found.isPresent(), "close() must let queued writes finish before the pool shuts down");
    }

    @Test
    void aMissingForeignRowLoadsAsNullInsteadOfHanging() {
        register(ChildTable.class);
        ParentTable parentTable = register(ParentTable.class);

        parentTable.add(new ParentObject("orphan-parent", new ChildObject("ghost-child", "Ghost")));
        awaitBarrier();

        reopen();
        register(ChildTable.class);
        ParentTable reloaded = register(ParentTable.class);

        Optional<ParentObject> found = Await.<Optional<ParentObject>>value(cb -> reloaded.getDataFromDB("orphan-parent", true, true, cb));

        assertTrue(found.isPresent());
        assertNull(found.get().getChild());
    }

    @Test
    void aRolledBackDeleteRestoresTheCachedObject() throws Exception {
        TestTable table = register(TestTable.class);
        TestObject object = new TestObject("keep-me", "Keep", null);

        table.add(object);
        awaitBarrier();

        boolean committed = bormAPI.transaction(tx -> {
            try {
                table.delete(object, tx);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            throw new RuntimeException("simulated failure after the delete");
        }).get(5, TimeUnit.SECONDS);

        assertFalse(committed);
        assertTrue(table.getDataMap().containsKey("keep-me"), "a rolled back delete must not leave the object out of the cache");
    }

    @Test
    void afterCommitHooksOnlyRunWhenTheTransactionCommits() throws Exception {
        boolean[] hookRan = {false};

        boolean committed = bormAPI.transaction(tx -> tx.addAfterCommitHook(() -> hookRan[0] = true)).get(5, TimeUnit.SECONDS);
        assertTrue(committed);
        assertTrue(hookRan[0]);

        hookRan[0] = false;

        boolean rolledBack = bormAPI.transaction(tx -> {
            tx.addAfterCommitHook(() -> hookRan[0] = true);
            throw new RuntimeException("simulated failure");
        }).get(5, TimeUnit.SECONDS);

        assertFalse(rolledBack);
        assertFalse(hookRan[0]);
    }

    @Test
    void unsafeTableNamesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new BormTable<>(bormAPI, TestObject.class, "test_object; DROP TABLE test_object"));
    }

    @Test
    void workAfterCloseDoesNotThrow() throws TimeoutException {
        bormAPI.close();

        CompletableFuture<Void> future = bormAPI.executeStatement("SELECT 1;");

        assertTrue(future.isCompletedExceptionally());
    }

}
