package net.bitbylogic.orm.processor;

import net.bitbylogic.orm.processor.impl.BukkitLocationProcessor;
import net.bitbylogic.orm.processor.impl.DefaultFieldProcessor;
import net.bitbylogic.orm.processor.impl.StringListProcessor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ProcessorTest {

    @Test
    void locationRoundTripsThroughItsStringForm() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world_nether");

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world_nether")).thenReturn(world);

            BukkitLocationProcessor processor = new BukkitLocationProcessor();

            Object stored = processor.processTo(new Location(world, 10.7, 64.2, -3.9));
            assertEquals("world_nether:10:64:-4", stored);

            Location loaded = processor.processFrom(stored);
            assertSame(world, loaded.getWorld());
            assertEquals(10, loaded.getX());
            assertEquals(64, loaded.getY());
            assertEquals(-4, loaded.getZ());
        }
    }

    @Test
    void locationWithoutAWorldOrValueStoresNull() {
        BukkitLocationProcessor processor = new BukkitLocationProcessor();

        assertNull(processor.processTo(null));
        assertNull(processor.processTo(new Location(null, 1, 2, 3)));
        assertNull(processor.processFrom(null));
    }

    @Test
    void stringListsRoundTripAndNullBecomesEmpty() {
        StringListProcessor processor = new StringListProcessor();

        Object stored = processor.processTo(List.of("a", "b", "c"));

        assertEquals(List.of("a", "b", "c"), processor.processFrom(stored));
        assertTrue(processor.processFrom(null).isEmpty());
    }

    @Test
    void defaultProcessorStoresBooleansAsDigitsAndKeepsBytes() {
        DefaultFieldProcessor processor = new DefaultFieldProcessor();
        byte[] bytes = {1, 2, 3};

        assertEquals("1", processor.processTo(true));
        assertEquals("0", processor.processTo(false));
        assertSame(bytes, processor.processTo(bytes));
        assertEquals("42", processor.processTo(42));
        assertEquals("raw", processor.processFrom("raw"));
    }

}
