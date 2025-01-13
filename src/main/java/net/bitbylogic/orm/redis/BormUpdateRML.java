package net.bitbylogic.orm.redis;

import net.bitbylogic.orm.data.BormObject;
import net.bitbylogic.orm.data.BormTable;
import net.bitbylogic.rps.listener.ListenerComponent;
import net.bitbylogic.rps.listener.RedisMessageListener;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public class BormUpdateRML<O extends BormObject> extends RedisMessageListener {

    private final BormTable<O> bormTable;

    public BormUpdateRML(BormTable<O> bormTable) {
        super("borm-update");
        this.bormTable = bormTable;
    }

    @Override
    public void onReceive(ListenerComponent component) {
        BormRedisUpdateType updateType = component.getData("updateType", BormRedisUpdateType.class);
        String objectId = component.getData("objectId", String.class);

        Optional<O> optionalObject = bormTable.getDataById(objectId);

        if (optionalObject.isEmpty()) {
            return;
        }

        O object = optionalObject.get();

        Executor delayedExecutor = CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS);

        CompletableFuture.runAsync(() -> {
            switch (updateType) {
                case SAVE:
                    bormTable.getDataMap().remove(bormTable.getStatements().getId(object));
                    bormTable.getDataFromDB(objectId, true, true, o -> {});
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
