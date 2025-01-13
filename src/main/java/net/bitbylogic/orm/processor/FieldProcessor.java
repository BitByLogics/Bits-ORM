package net.bitbylogic.orm.processor;

public interface FieldProcessor<O> {

    Object processTo(O object);

    O processFrom(Object object);

}
