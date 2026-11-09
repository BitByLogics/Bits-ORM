package net.bitbylogic.orm;

import lombok.NonNull;
import net.bitbylogic.orm.data.BormTable;

public class PreloadedTable extends BormTable<TestObject> {

    public PreloadedTable(@NonNull BormAPI bormAPI) {
        super(bormAPI, TestObject.class, "test_object", true);
    }

}
