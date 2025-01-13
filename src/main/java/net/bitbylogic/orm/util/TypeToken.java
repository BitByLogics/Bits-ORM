package net.bitbylogic.orm.util;

import lombok.Getter;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

@Getter
public class TypeToken<T> {

    protected Type type;

    protected TypeToken() {
        this.type = ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
    }

    public static <T> TypeToken<T> asTypeToken(Type methodType) {
        return new TypeToken<T>() {
            @Override
            public Type getType() {
                return methodType;
            }
        };
    }

}
