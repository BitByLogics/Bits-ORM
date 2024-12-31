package net.bitbylogic.orm.redis;

import net.bitbylogic.orm.data.HikariObject;
import net.bitbylogic.orm.data.HikariTable;
import net.bitbylogic.rps.listener.ListenerComponent;
import net.bitbylogic.rps.listener.RedisMessageListener;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public class HikariUpdateRedisMessageListener<O extends HikariObject> extends RedisMessageListener {

    private final HikariTable<O> hikariTable;

    public HikariUpdateRedisMessageListener(HikariTable<O> hikariTable) {
        super("hikari-update");
        this.hikariTable = hikariTable;
    }

    @Override
    public void onReceive(ListenerComponent component) {
        HikariRedisUpdateType updateType = component.getData("updateType", HikariRedisUpdateType.class);
        String objectId = component.getData("objectId", String.class);

        Optional<O> optionalObject = hikariTable.getDataById(objectId);

        if (optionalObject.isEmpty()) {
            return;
        }

        O object = optionalObject.get();

        Executor delayedExecutor = CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS);

        CompletableFuture.runAsync(() -> {
            switch (updateType) {
                case SAVE:
                    hikariTable.getDataMap().remove(hikariTable.getStatements().getId(object));
                    hikariTable.getDataFromDB(objectId, true, true, o -> {});
                    break;
                case DELETE:
                    hikariTable.getDataMap().remove(hikariTable.getStatements().getId(object));
                    break;
                default:
                    break;
            }
        }, delayedExecutor);
    }

}
