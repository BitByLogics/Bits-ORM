package net.bitbylogic.orm;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import net.bitbylogic.orm.annotation.Column;
import net.bitbylogic.orm.data.BormObject;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ParentListObject extends BormObject {

    @Column(primaryKey = true)
    private String id;

    @Column(foreignTable = "child_object", cascadeDelete = true)
    private List<ChildObject> children;

}
