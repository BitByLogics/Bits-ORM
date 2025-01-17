package net.bitbylogic.orm.data;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.Setter;

@SuppressWarnings("ALL")
@NoArgsConstructor
public class BormObject {

    @Setter(AccessLevel.PROTECTED)
    protected BormTable owningTable;

    public void save() {
        if(owningTable == null) {
            return;
        }

        owningTable.save(this);
    }

    public void delete() {
        if(owningTable == null) {
            return;
        }

        owningTable.delete(this);
    }

}
