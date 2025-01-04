package net.bitbylogic.orm.processor.impl;

import net.bitbylogic.orm.processor.FieldProcessor;

public class DefaultFieldProcessor implements FieldProcessor<Object> {

    @Override
    public Object parseToObject(Object fieldValue) {
        if (fieldValue instanceof Boolean) {
            return String.valueOf((Boolean) fieldValue ? 1 : 0);
        }

        return fieldValue.toString();
    }

    @Override
    public Object parseFromObject(Object object) {
        return object;
    }

}
