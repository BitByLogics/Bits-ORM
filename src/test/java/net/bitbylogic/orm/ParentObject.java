package net.bitbylogic.orm;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import net.bitbylogic.orm.annotation.Column;
import net.bitbylogic.orm.data.BormObject;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ParentObject extends BormObject {

    @Column(primaryKey = true)
    private String id;

    @Column(foreignTable = "child_object", cascadeDelete = true)
    private ChildObject child;

}
