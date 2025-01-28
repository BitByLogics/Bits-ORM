package net.bitbylogic.orm.processor.impl;

import net.bitbylogic.orm.processor.FieldProcessor;
import org.bukkit.Bukkit;
import org.bukkit.Location;

public class BukkitLocationProcessor implements FieldProcessor<Location> {

    @Override
    public Object processTo(Location location) {
        if(location == null || location.getWorld() == null) {
            return "NULL";
        }

        return String.format("%s:%s:%s:%s", location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    @Override
    public Location processFrom(Object object) {
        if(object == null) {
            return null;
        }

        String[] splitArgs = ((String) object).split(":");
        return new Location(Bukkit.getWorld(splitArgs[0]), Double.parseDouble(splitArgs[1]), Double.parseDouble(splitArgs[2]), Double.parseDouble(splitArgs[3]));
    }

}
