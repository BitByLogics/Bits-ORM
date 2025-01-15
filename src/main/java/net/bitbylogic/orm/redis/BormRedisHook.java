package net.bitbylogic.orm.redis;

import lombok.NonNull;
import net.bitbylogic.orm.BormAPI;
import net.bitbylogic.rps.client.RedisClient;
import net.bitbylogic.rps.listener.ListenerComponent;

public class BormRedisHook {

    private final @NonNull RedisClient redisClient;

    public BormRedisHook(@NonNull BormAPI bormAPI, @NonNull RedisClient redisClient) {
        this.redisClient = redisClient;

        redisClient.registerListener(new BormUpdateRML(bormAPI));
    }

    public void sendChange(@NonNull BormRedisUpdateType updateType, @NonNull String table, @NonNull String objectId) {
        redisClient.sendListenerMessage(
                new ListenerComponent(null, "borm-update")
                        .addData("updateType", updateType)
                        .addData("tableName", table)
                        .addData("objectId", objectId)
        );
    }

}
