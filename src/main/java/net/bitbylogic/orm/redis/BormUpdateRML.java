package net.bitbylogic.orm.redis;

import lombok.NonNull;
import net.bitbylogic.orm.BormAPI;
import net.bitbylogic.orm.DatabaseType;
import net.bitbylogic.orm.data.BormObject;
import net.bitbylogic.orm.data.BormTable;
import net.bitbylogic.rps.listener.ListenerComponent;
import net.bitbylogic.rps.listener.RedisMessageListener;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class BormUpdateRML extends RedisMessageListener {

    private final @NonNull BormAPI bormAPI;

    public BormUpdateRML(@NonNull BormAPI bormAPI) {
        super("borm-update");
        this.bormAPI = bormAPI;
    }

    @Override
    public void onReceive(ListenerComponent component) {
        BormRedisUpdateType updateType = component.getData("updateType", BormRedisUpdateType.class);
        String tableName = component.getData("tableName", String.class);

        if (updateType == null || tableName == null) {
            return;
        }

        BormTable<?> bormTable = bormAPI.getTable(tableName);

        if (bormTable == null) {
            return;
        }

        if (bormAPI.getType() == DatabaseType.SQLITE) {
            return;
        }

        Executor delayedExecutor = CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS);

        if (updateType == BormRedisUpdateType.SAVE_ALL) {
            run(() -> bormTable.loadData(() -> {
            }), delayedExecutor);
            return;
        }

        String objectId = component.getData("objectId", String.class);

        if (objectId == null) {
            return;
        }

        Optional<? extends BormObject> optionalObject = bormTable.getDataById(objectId);

        if (optionalObject.isEmpty()) {
            return;
        }

        Object cacheId = bormTable.getStatements().getId(optionalObject.get());

        if (cacheId == null) {
            return;
        }

        switch (updateType) {
            case SAVE:
                run(() -> bormTable.refresh(cacheId, null), delayedExecutor);
                break;
            case DELETE:
                run(() -> bormTable.unload(cacheId), delayedExecutor);
                break;
            default:
                break;
        }
    }

    private void run(Runnable task, Executor executor) {
        CompletableFuture.runAsync(task, executor).exceptionally(throwable -> {
            bormAPI.getLogger().log(Level.SEVERE, "Failed to apply a Redis update", throwable);
            return null;
        });
    }

}
