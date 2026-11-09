package net.bitbylogic.orm;

import lombok.NonNull;
import net.bitbylogic.orm.data.BormTable;

public class TestTable extends BormTable<TestObject> {

    public TestTable(@NonNull BormAPI bormAPI) {
        super(bormAPI, TestObject.class, "test_object");
    }

}
