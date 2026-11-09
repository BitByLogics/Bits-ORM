package net.bitbylogic.orm;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WriteOrderingTest {

    private File dbFile;
    private BormAPI bormAPI;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        dbFile = tempDir.resolve("borm-ordering-test.db").toFile();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbFile);
        config.setMaximumPoolSize(4);
        config.setConnectionInitSql("PRAGMA busy_timeout=10000;");

        ExecutorService multiThreaded = Executors.newFixedThreadPool(4);
        bormAPI = new BormAPI(config, multiThreaded);
    }

    @AfterEach
    void tearDown() {
        bormAPI.close();
    }

    @Test
    void writesToOneTableRunInSubmissionOrderOnAMultiThreadedExecutor() throws Exception {
        TestTable table = Await.<TestTable>value(cb -> bormAPI.registerTable(TestTable.class, cb));

        for (int i = 0; i < 100; i++) {
            TestObject object = new TestObject("ordered-" + i, "Ordered", null);

            table.add(object);
            table.delete(object);
        }

        bormAPI.executeOrderedStatement(table.getTable(), "DELETE FROM test_object WHERE 1 = 0;", null).get(10, TimeUnit.SECONDS);

        AtomicInteger remainingRows = new AtomicInteger(-1);
        bormAPI.executeQuery("SELECT COUNT(*) FROM test_object;", rs -> {
            try {
                rs.next();
                remainingRows.set(rs.getInt(1));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).get(10, TimeUnit.SECONDS);

        assertEquals(0, remainingRows.get(), "every delete must run after the save it followed");
    }

    @Test
    void queriesSeeEveryWriteSubmittedBeforeThem() {
        TestTable table = Await.<TestTable>value(cb -> bormAPI.registerTable(TestTable.class, cb));

        for (int i = 0; i < 50; i++) {
            table.add(new TestObject("seen-" + i, "Seen", null));
        }

        List<TestObject> results = Await.<List<TestObject>>value(cb -> table.query().execute(cb));

        assertEquals(50, results.size());
    }

    @Test
    void closeWaitsForChainedWrites() throws Exception {
        TestTable table = Await.<TestTable>value(cb -> bormAPI.registerTable(TestTable.class, cb));

        for (int i = 0; i < 50; i++) {
            table.add(new TestObject("chained-" + i, "Chained", null));
        }

        bormAPI.close();

        bormAPI = new BormAPI(dbFile);

        AtomicInteger rows = new AtomicInteger(-1);
        bormAPI.executeQuery("SELECT COUNT(*) FROM test_object;", rs -> {
            try {
                rs.next();
                rows.set(rs.getInt(1));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).get(10, TimeUnit.SECONDS);

        assertEquals(50, rows.get(), "close() must let chained writes finish before shutting the pool down");
    }

}
