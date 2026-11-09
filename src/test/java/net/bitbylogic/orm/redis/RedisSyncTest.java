package net.bitbylogic.orm.redis;

import net.bitbylogic.orm.Await;
import net.bitbylogic.orm.BormAPI;
import net.bitbylogic.orm.DatabaseType;
import net.bitbylogic.orm.TestObject;
import net.bitbylogic.orm.TestTable;
import net.bitbylogic.rps.client.RedisClient;
import net.bitbylogic.rps.listener.ListenerComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RedisSyncTest {

    private BormAPI bormAPI;
    private TestTable table;
    private BormUpdateRML listener;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        bormAPI = new BormAPI(tempDir.resolve("borm-redis-test.db").toFile());
        table = Await.<TestTable>value(cb -> bormAPI.registerTable(TestTable.class, cb));
        listener = new BormUpdateRML(bormAPI);
    }

    @AfterEach
    void tearDown() {
        bormAPI.close();
    }

    private ListenerComponent message(BormRedisUpdateType type, String objectId) {
        return new ListenerComponent(null, "borm-update")
                .addData("updateType", type)
                .addData("tableName", "test_object")
                .addData("objectId", objectId);
    }

    private void awaitBarrier() {
        Await.run(cb -> bormAPI.executeQuery("SELECT 1;", rs -> cb.run()));
    }

    @Test
    void hookRegistersItsListenerAndSendsChanges() {
        RedisClient client = mock(RedisClient.class);
        BormRedisHook hook = new BormRedisHook(bormAPI, client);

        verify(client).registerListener(org.mockito.ArgumentMatchers.any(BormUpdateRML.class));

        hook.sendChange(BormRedisUpdateType.SAVE, "test_object", "abc");

        ArgumentCaptor<ListenerComponent> captor = ArgumentCaptor.forClass(ListenerComponent.class);
        verify(client).sendListenerMessage(captor.capture());

        assertEquals("borm-update", captor.getValue().getChannel());
        assertEquals("test_object", captor.getValue().getData("tableName", String.class));
        assertEquals("abc", captor.getValue().getData("objectId", String.class));
        assertEquals(BormRedisUpdateType.SAVE, captor.getValue().getData("updateType", BormRedisUpdateType.class));
    }

    @Test
    void saveMessageRefreshesTheCachedObjectFromTheDatabase() {
        bormAPI.setType(DatabaseType.MYSQL);

        TestObject original = new TestObject("r-1", "Before", null);
        table.add(original);
        awaitBarrier();

        bormAPI.executeStatement("UPDATE test_object SET name = 'After' WHERE id = 'r-1';");
        listener.onReceive(message(BormRedisUpdateType.SAVE, "r-1"));

        Await.until(() -> {
            TestObject cached = table.getDataMap().get("r-1");
            return cached != null && "After".equals(cached.getName());
        }, "the cached object was not refreshed after a SAVE message");

        assertNotSame(original, table.getDataMap().get("r-1"));
    }

    @Test
    void deleteMessageEvictsTheCachedObject() {
        bormAPI.setType(DatabaseType.MYSQL);

        table.add(new TestObject("r-2", "Gone", null));
        awaitBarrier();

        listener.onReceive(message(BormRedisUpdateType.DELETE, "r-2"));

        Await.until(() -> !table.getDataMap().containsKey("r-2"), "the cached object was not evicted after a DELETE message");
    }

    @Test
    void saveAllMessageReloadsTheWholeTable() {
        bormAPI.setType(DatabaseType.MYSQL);

        bormAPI.executeStatement("INSERT INTO test_object (id, name, payload) VALUES ('r-3', 'External', NULL);");
        awaitBarrier();

        listener.onReceive(message(BormRedisUpdateType.SAVE_ALL, ""));

        Await.until(() -> table.getDataMap().containsKey("r-3"), "the table was not reloaded after a SAVE_ALL message");
    }

    @Test
    void messagesForUnknownTablesObjectsOrSqliteAreIgnored() {
        table.add(new TestObject("r-4", "Stay", null));
        awaitBarrier();

        listener.onReceive(message(BormRedisUpdateType.DELETE, "r-4"));

        bormAPI.setType(DatabaseType.MYSQL);
        listener.onReceive(new ListenerComponent(null, "borm-update")
                .addData("updateType", BormRedisUpdateType.DELETE)
                .addData("tableName", "no_such_table")
                .addData("objectId", "r-4"));
        listener.onReceive(message(BormRedisUpdateType.DELETE, "not-cached"));
        listener.onReceive(new ListenerComponent(null, "borm-update"));

        awaitBarrier();

        assertTrue(table.getDataMap().containsKey("r-4"));
    }

}
