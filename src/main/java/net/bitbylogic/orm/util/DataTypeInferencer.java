package net.bitbylogic.orm.util;

import lombok.RequiredArgsConstructor;
import net.bitbylogic.utils.StringProcessor;

@RequiredArgsConstructor
public enum DataTypeInferencer {

    DEFAULT(new Class[] {String.class}, "TEXT"),
    INT(new Class[]{Integer.class, int.class}, "INT"),
    LONG(new Class[]{Long.class, long.class}, "LONG"),
    DOUBLE(new Class[]{Double.class, double.class}, "DOUBLE"),
    FLOAT(new Class[]{Float.class,float.class}, "FLOAT"),
    SHORT(new Class[]{Short.class, short.class}, "SMALLINT"),
    BYTE(new Class[]{Byte.class, byte.class}, "TINYINT"),
    BOOLEAN(new Class[]{Boolean.class, boolean.class}, "BOOLEAN"),
    CHAR(new Class[]{Character.class, char.class}, "CHAR");

    private final Class<?>[] dataTypes;
    private final String dataType;

    public static String inferDataType(Class<?> targetClass) {
        for (DataTypeInferencer inferencer : values()) {
            for (Class<?> dataTypeClass : inferencer.dataTypes) {
                if (!dataTypeClass.isAssignableFrom(targetClass)) {
                    continue;
                }

                return inferencer.dataType;
            }
        }

        return DEFAULT.dataType;
    }

}
