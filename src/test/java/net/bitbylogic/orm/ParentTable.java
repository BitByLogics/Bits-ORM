package net.bitbylogic.orm;

import lombok.NonNull;
import net.bitbylogic.orm.data.BormTable;

public class ParentTable extends BormTable<ParentObject> {

    public ParentTable(@NonNull BormAPI bormAPI) {
        super(bormAPI, ParentObject.class, "parent_object");
    }

}
