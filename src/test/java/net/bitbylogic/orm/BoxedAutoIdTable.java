package net.bitbylogic.orm;

import lombok.NonNull;
import net.bitbylogic.orm.data.BormTable;

public class BoxedAutoIdTable extends BormTable<BoxedAutoIdObject> {

    public BoxedAutoIdTable(@NonNull BormAPI bormAPI) {
        super(bormAPI, BoxedAutoIdObject.class, "boxed_auto_id_object");
    }

}
