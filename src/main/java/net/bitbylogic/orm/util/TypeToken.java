package net.bitbylogic.orm.util;

import lombok.Getter;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

@Getter
public class TypeToken<T> {

    private final Type type;

    protected TypeToken() {
        this.type = ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof TypeToken && type.equals(((TypeToken<?>) obj).getType());
    }

    @Override
    public int hashCode() {
        return type.hashCode();
    }

    public static <T> TypeToken<T> asTypeToken(Type type) {
        return new TypeToken<T>() {
            @Override
            public Type getType() {
                return type;
            }
        };
    }

}
