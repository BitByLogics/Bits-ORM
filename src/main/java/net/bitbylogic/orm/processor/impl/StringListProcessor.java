package net.bitbylogic.orm.processor.impl;

import com.google.gson.reflect.TypeToken;
import net.bitbylogic.orm.processor.FieldProcessor;
import net.bitbylogic.utils.ListUtil;

import java.util.List;

public class StringListProcessor implements FieldProcessor<List<String>> {

    @Override
    public Object processTo(List<String> o) {
        return ListUtil.listToString(o);
    }

    @Override
    public List<String> processFrom(Object o) {
        return (List<String>) ListUtil.stringToList((String) o);
    }

}
