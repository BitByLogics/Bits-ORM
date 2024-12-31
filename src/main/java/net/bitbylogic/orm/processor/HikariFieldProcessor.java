package net.bitbylogic.orm.processor;

public interface HikariFieldProcessor<O> {

    Object parseToObject(O object);

    O parseFromObject(Object object);

}
