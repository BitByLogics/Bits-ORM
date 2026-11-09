package net.bitbylogic.orm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SubClassTest {

    private File dbFile;
    private BormAPI bormAPI;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        dbFile = tempDir.resolve("borm-subclass-test.db").toFile();
        bormAPI = new BormAPI(dbFile);
    }

    @AfterEach
    void tearDown() {
        bormAPI.close();
    }

    private void reopen() {
        bormAPI.close();
        bormAPI = new BormAPI(dbFile);
    }

    private AddressHolderTable registerTable() {
        return Await.<AddressHolderTable>value(cb -> bormAPI.registerTable(AddressHolderTable.class, cb));
    }

    @Test
    void tableConstructionDoesNotThrowWhenSubClassFieldIsUninitialized() {
        assertDoesNotThrow(() -> new AddressHolderTable(bormAPI));
    }

    @Test
    void twoSubClassFieldsOfTheSameTypeDoNotCollide() {
        AddressHolderTable table = registerTable();

        AddressHolderObject holder = new AddressHolderObject("holder-1",
                "Alice", "Springfield",
                "Bob", "Shelbyville");

        Await.run(cb -> table.save(holder, v -> cb.run()));

        reopen();
        AddressHolderTable reloaded = registerTable();

        Optional<AddressHolderObject> found = Await.<Optional<AddressHolderObject>>value(
                cb -> reloaded.getDataFromDB("holder-1", true, true, cb));

        assertTrue(found.isPresent());

        AddressHolderObject result = found.get();
        assertEquals("Alice", result.getBillingAddress().getName());
        assertEquals("Springfield", result.getBillingAddress().getCity());
        assertEquals("Bob", result.getShippingAddress().getName());
        assertEquals("Shelbyville", result.getShippingAddress().getCity());
    }

}
