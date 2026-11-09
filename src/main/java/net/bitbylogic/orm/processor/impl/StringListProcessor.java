package net.bitbylogic.orm.processor.impl;

import net.bitbylogic.orm.processor.FieldProcessor;
import net.bitbylogic.utils.ListUtil;

import java.util.ArrayList;
import java.util.List;

public class StringListProcessor implements FieldProcessor<List<String>> {

    @Override
    public Object processTo(List<String> o) {
        return ListUtil.listToString(o);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> processFrom(Object o) {
        if (o == null) {
            return new ArrayList<>();
        }

        return (List<String>) ListUtil.stringToList((String) o);
    }

}
