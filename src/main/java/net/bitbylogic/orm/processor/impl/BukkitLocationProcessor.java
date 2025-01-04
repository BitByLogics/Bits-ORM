package net.bitbylogic.orm.processor.impl;

import net.bitbylogic.orm.processor.FieldProcessor;
import org.bukkit.Bukkit;
import org.bukkit.Location;

public class BukkitLocationProcessor implements FieldProcessor<Location> {

    @Override
    public Object parseToObject(Location location) {
        if(location.getWorld() == null) {
            return "NULL";
        }

        return String.format("%s:%s:%s:%s", location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    @Override
    public Location parseFromObject(Object object) {
        String[] splitArgs = ((String) object).split(":");
        return new Location(Bukkit.getWorld(splitArgs[0]), Double.parseDouble(splitArgs[1]), Double.parseDouble(splitArgs[2]), Double.parseDouble(splitArgs[3]));
    }

}
