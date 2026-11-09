package net.bitbylogic.orm;

import lombok.NonNull;
import net.bitbylogic.orm.data.BormTable;

public class ParentListTable extends BormTable<ParentListObject> {

    public ParentListTable(@NonNull BormAPI bormAPI) {
        super(bormAPI, ParentListObject.class, "parent_list_object");
    }

}
