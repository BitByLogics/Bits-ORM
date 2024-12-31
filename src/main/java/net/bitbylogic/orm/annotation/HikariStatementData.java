package net.bitbylogic.orm.annotation;

import net.bitbylogic.orm.processor.HikariFieldProcessor;
import net.bitbylogic.orm.processor.impl.DefaultHikariFieldProcessor;

import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface HikariStatementData {

    String dataType() default "";

    String columnName() default "";

    boolean allowNull() default true;

    boolean autoIncrement() default false;

    boolean primaryKey() default false;

    boolean updateOnSave() default true;

    boolean subClass() default false;

    Class<? extends HikariFieldProcessor<?>> processor() default DefaultHikariFieldProcessor.class;

    String foreignTable() default "";

    boolean foreignDelete() default false;

}
