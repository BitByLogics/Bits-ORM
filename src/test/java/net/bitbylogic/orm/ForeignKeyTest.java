package net.bitbylogic.orm;

import net.bitbylogic.orm.data.BormObject;
import net.bitbylogic.orm.data.BormTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ForeignKeyTest {

    private File dbFile;
    private BormAPI bormAPI;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        dbFile = tempDir.resolve("borm-fk-test.db").toFile();
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

    private void awaitBarrier() {
        Await.run(cb -> bormAPI.executeQuery("SELECT 1;", rs -> cb.run()));
    }

    private <O extends BormObject> void addAndWait(BormTable<O> table, O object) {
        table.add(object);
        awaitBarrier();
    }

    private <O extends BormObject, T extends BormTable<O>> T registerAndWait(Class<T> tableClass) {
        return Await.<T>value(cb -> bormAPI.registerTable(tableClass, cb));
    }

    private <O extends BormObject> Optional<O> load(BormTable<O> table, Object id) {
        return Await.<Optional<O>>value(cb -> table.getDataFromDB(id, true, true, cb));
    }

    @Test
    void singleForeignObjectRoundTrips() {
        ChildTable childTable = registerAndWait(ChildTable.class);
        ParentTable parentTable = registerAndWait(ParentTable.class);

        ChildObject child = new ChildObject("child-1", "First Child");
        addAndWait(childTable, child);

        ParentObject parent = new ParentObject("parent-1", child);
        addAndWait(parentTable, parent);

        reopen();
        registerAndWait(ChildTable.class);
        ParentTable reloadedParentTable = registerAndWait(ParentTable.class);

        Optional<ParentObject> found = load(reloadedParentTable, "parent-1");
        assertTrue(found.isPresent());
        assertNotNull(found.get().getChild());
        assertEquals("First Child", found.get().getChild().getLabel());
    }

    @Test
    void listOfForeignObjectsRoundTripsInOrder() {
        ChildTable childTable = registerAndWait(ChildTable.class);
        ParentListTable parentTable = registerAndWait(ParentListTable.class);

        ChildObject childA = new ChildObject("child-a", "A");
        ChildObject childB = new ChildObject("child-b", "B");
        ChildObject childC = new ChildObject("child-c", "C");
        addAndWait(childTable, childA);
        addAndWait(childTable, childB);
        addAndWait(childTable, childC);

        ParentListObject parent = new ParentListObject("parent-list-1", List.of(childA, childB, childC));
        addAndWait(parentTable, parent);

        reopen();
        registerAndWait(ChildTable.class);
        ParentListTable reloadedParentTable = registerAndWait(ParentListTable.class);

        Optional<ParentListObject> found = load(reloadedParentTable, "parent-list-1");
        assertTrue(found.isPresent());

        List<ChildObject> children = found.get().getChildren();
        assertEquals(3, children.size());
        assertEquals(List.of("A", "B", "C"), children.stream().map(ChildObject::getLabel).toList());
    }

    @Test
    void cascadeDeleteRemovesChildren() {
        ChildTable childTable = registerAndWait(ChildTable.class);
        ParentListTable parentTable = registerAndWait(ParentListTable.class);

        ChildObject childA = new ChildObject("cd-child-a", "A");
        ChildObject childB = new ChildObject("cd-child-b", "B");
        addAndWait(childTable, childA);
        addAndWait(childTable, childB);

        ParentListObject parent = new ParentListObject("cd-parent", List.of(childA, childB));
        addAndWait(parentTable, parent);

        parentTable.delete(parent);
        awaitBarrier();

        reopen();
        ChildTable reloadedChildTable = registerAndWait(ChildTable.class);

        Optional<ChildObject> foundA = load(reloadedChildTable, "cd-child-a");
        Optional<ChildObject> foundB = load(reloadedChildTable, "cd-child-b");

        assertTrue(foundA.isEmpty());
        assertTrue(foundB.isEmpty());
    }

}
