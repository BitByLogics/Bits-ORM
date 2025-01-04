package net.bitbylogic.orm.processor;

public interface FieldProcessor<O> {

    Object parseToObject(O object);

    O parseFromObject(Object object);

}
