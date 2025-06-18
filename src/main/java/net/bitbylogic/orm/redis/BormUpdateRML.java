package net.bitbylogic.orm.redis;

import lombok.NonNull;
import net.bitbylogic.orm.BormAPI;
import net.bitbylogic.orm.DatabaseType;
import net.bitbylogic.orm.data.BormObject;
import net.bitbylogic.orm.data.BormTable;
import net.bitbylogic.rps.listener.ListenerComponent;
import net.bitbylogic.rps.listener.RedisMessageListener;

import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

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
        String objectId = component.getData("objectId", String.class);

        BormTable bormTable = bormAPI.getTable(tableName);

        if(bormTable == null) {
            return;
        }

        Optional<BormObject> optionalObject = bormTable.getDataById(objectId);

        if (optionalObject.isEmpty()) {
            return;
        }

        BormObject object = optionalObject.get();
        Executor delayedExecutor = CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS);

        CompletableFuture.runAsync(() -> {
            switch (updateType) {
                case SAVE:
                    if(bormAPI.getType() != DatabaseType.SQLITE) {
                        bormTable.getDataMap().remove(bormTable.getStatements().getId(object));
                        bormTable.getDataFromDB(objectId, false, true, o -> {});
                        break;
                    }

                    break;
                case DELETE:
                    bormTable.getDataMap().remove(bormTable.getStatements().getId(object));
                    break;
                default:
                    break;
            }
        }, delayedExecutor);
    }

}
