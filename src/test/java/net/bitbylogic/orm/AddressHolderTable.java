package net.bitbylogic.orm;

import lombok.NonNull;
import net.bitbylogic.orm.data.BormTable;

public class AddressHolderTable extends BormTable<AddressHolderObject> {

    public AddressHolderTable(@NonNull BormAPI bormAPI) {
        super(bormAPI, AddressHolderObject.class, "address_holder");
    }

}
