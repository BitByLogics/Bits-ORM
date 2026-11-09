package net.bitbylogic.orm.query;

public enum QueryOperator {

    EQUALS("="),
    NOT_EQUALS("!="),
    GREATER_THAN(">"),
    GREATER_THAN_OR_EQUAL(">="),
    LESS_THAN("<"),
    LESS_THAN_OR_EQUAL("<="),
    LIKE("LIKE");

    private final String sql;

    QueryOperator(String sql) {
        this.sql = sql;
    }

    public String getSql() {
        return sql;
    }

}
