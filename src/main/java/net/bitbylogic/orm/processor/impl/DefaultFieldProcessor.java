package net.bitbylogic.orm.processor.impl;

import net.bitbylogic.orm.processor.FieldProcessor;

public class DefaultFieldProcessor implements FieldProcessor<Object> {

    @Override
    public Object processTo(Object fieldValue) {
        if (fieldValue instanceof Boolean) {
            return String.valueOf((Boolean) fieldValue ? 1 : 0);
        }

        if (fieldValue instanceof byte[]) {
            return fieldValue;
        }

        return fieldValue.toString();
    }

    @Override
    public Object processFrom(Object object) {
        return object;
    }

}
