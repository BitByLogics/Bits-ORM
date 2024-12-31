package net.bitbylogic.orm.processor.impl;

import net.bitbylogic.orm.processor.HikariFieldProcessor;
import net.bitbylogic.utils.ListUtil;

import java.util.List;

public class StringListProcessor implements HikariFieldProcessor<List<String>> {

    @Override
    public Object parseToObject(List<String> o) {
        return ListUtil.listToString(o);
    }

    @Override
    public List<String> parseFromObject(Object o) {
        return (List<String>) ListUtil.stringToList((String) o);
    }

}
