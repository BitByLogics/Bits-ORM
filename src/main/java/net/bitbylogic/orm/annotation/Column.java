package net.bitbylogic.orm.annotation;

import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface Column {

    String dataType() default "";

    String name() default "";

    boolean allowNull() default false;

    boolean autoIncrement() default false;

    boolean primaryKey() default false;

    boolean updateOnSave() default true;

    String foreignTable() default "";

    boolean cascadeDelete() default false;

}
