package net.bitbylogic.orm;

import lombok.NonNull;
import net.bitbylogic.orm.data.BormTable;

public class AutoIdTable extends BormTable<AutoIdObject> {

    public AutoIdTable(@NonNull BormAPI bormAPI) {
        super(bormAPI, AutoIdObject.class, "auto_id_object");
    }

}
