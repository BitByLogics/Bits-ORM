package net.bitbylogic.orm;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import net.bitbylogic.orm.annotation.Column;
import net.bitbylogic.orm.data.BormObject;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AutoIdObject extends BormObject {

    @Column(primaryKey = true, autoIncrement = true)
    private int id;

    @Column
    private String label;

}
