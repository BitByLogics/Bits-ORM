package net.bitbylogic.orm;

import lombok.NonNull;
import net.bitbylogic.orm.data.BormTable;

public class ChildTable extends BormTable<ChildObject> {

    public ChildTable(@NonNull BormAPI bormAPI) {
        super(bormAPI, ChildObject.class, "child_object");
    }

}
