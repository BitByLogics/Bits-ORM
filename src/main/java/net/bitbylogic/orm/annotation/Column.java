package net.bitbylogic.orm.annotation;

import net.bitbylogic.orm.processor.FieldProcessor;
import net.bitbylogic.orm.processor.impl.DefaultFieldProcessor;

import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface Column {

    String dataType() default "";

    String name() default "";

    boolean allowNull() default true;

    boolean autoIncrement() default false;

    boolean primaryKey() default false;

    boolean updateOnSave() default true;

    boolean subClass() default false;

    Class<? extends FieldProcessor<?>> processor() default DefaultFieldProcessor.class;

    String foreignTable() default "";

    boolean foreignDelete() default false;

}
