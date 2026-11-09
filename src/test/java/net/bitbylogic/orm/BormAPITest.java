package net.bitbylogic.orm;

import net.bitbylogic.orm.processor.FieldProcessor;
import net.bitbylogic.orm.processor.impl.DefaultFieldProcessor;
import net.bitbylogic.orm.processor.impl.StringListProcessor;
import net.bitbylogic.utils.reflection.TypeToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BormAPITest {

    private Path tempDir;
    private BormAPI bormAPI;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        this.tempDir = tempDir;
        bormAPI = new BormAPI(tempDir.resolve("borm-api-test.db").toFile());
    }

    @AfterEach
    void tearDown() {
        bormAPI.close();
    }

    @Test
    void aTableThatNeedsAnotherTableWaitsForItAndLoadsAfterwards() throws Exception {
        CompletableFuture<ParentTable> parentLoaded = new CompletableFuture<>();

        bormAPI.registerTable(ParentTable.class, parentLoaded::complete);
        Await.until(() -> bormAPI.getTable("parent_object") != null, "the parent table was never registered");

        assertFalse(parentLoaded.isDone(), "the parent table must wait until child_object is loaded");

        ChildTable child = Await.<ChildTable>value(cb -> bormAPI.registerTable(ChildTable.class, cb));
        ParentTable parent = parentLoaded.get(5, TimeUnit.SECONDS);

        ChildObject childObject = new ChildObject("late-child", "Late");
        child.add(childObject);
        parent.add(new ParentObject("late-parent", childObject));

        assertSame(child, parent.getStatements().getColumnData("child").orElseThrow().getForeignTable());
    }

    @Test
    void registeringTheSameTableTwiceKeepsTheFirstOne() {
        TestTable first = Await.<TestTable>value(cb -> bormAPI.registerTable(TestTable.class, cb));
        AtomicInteger secondCallbacks = new AtomicInteger();

        bormAPI.registerTable(TestTable.class, table -> secondCallbacks.incrementAndGet());
        bormAPI.executeQuery("SELECT 1;", rs -> {
        });

        assertSame(first, bormAPI.getTable("TEST_OBJECT"));
        assertEquals(1, bormAPI.getTables().size());
        assertEquals(0, secondCallbacks.get());
    }

    @Test
    void theTableRegistryIsReadOnly() {
        assertThrows(UnsupportedOperationException.class, () -> bormAPI.getTables().clear());
    }

    @Test
    void aDatabaseFileUnderARegularFileIsRejected() throws Exception {
        File regularFile = Files.createFile(tempDir.resolve("not-a-directory")).toFile();

        assertThrows(IllegalStateException.class, () -> new BormAPI(new File(regularFile, "nested/borm.db")));
    }

    @Test
    void customFieldProcessorsAreUsedAndTheFirstRegistrationWins() {
        TypeToken<String> token = new TypeToken<>() {
        };
        FieldProcessor<String> processor = new FieldProcessor<>() {
            @Override
            public Object processTo(String value) {
                return value.toUpperCase();
            }

            @Override
            public String processFrom(Object value) {
                return String.valueOf(value);
            }
        };

        assertInstanceOf(DefaultFieldProcessor.class, bormAPI.getFieldProcessor(token));

        bormAPI.registerFieldProcessor(token, processor);
        bormAPI.registerFieldProcessor(token, new FieldProcessor<>() {
            @Override
            public Object processTo(String value) {
                return value;
            }

            @Override
            public String processFrom(Object value) {
                return String.valueOf(value);
            }
        });

        assertSame(processor, bormAPI.getFieldProcessor(token));
    }

    @Test
    void stringListsHaveADefaultProcessor() {
        TypeToken<java.util.List<String>> token = new TypeToken<>() {
        };

        assertInstanceOf(StringListProcessor.class, bormAPI.getFieldProcessor(token));
    }

    @Test
    void closingTwiceIsHarmless() {
        bormAPI.close();
        bormAPI.close();

        assertTrue(bormAPI.getDataSource().isClosed());
    }

    @Test
    void transactionsOnAClosedApiReportFailureInsteadOfThrowing() throws Exception {
        bormAPI.close();

        assertFalse(bormAPI.transaction(tx -> {
        }).get(5, TimeUnit.SECONDS));
    }

}
