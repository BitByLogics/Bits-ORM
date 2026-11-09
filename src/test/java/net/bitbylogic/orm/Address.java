package net.bitbylogic.orm;

import net.bitbylogic.orm.annotation.Column;

/**
 * A plain embedded value object (not a BormObject) used to test @Column(subClass = true).
 */
public class Address {

    @Column
    private String name;

    @Column
    private String city;

    public Address() {
    }

    public Address(String name, String city) {
        this.name = name;
        this.city = city;
    }

    public String getName() {
        return name;
    }

    public String getCity() {
        return city;
    }

}
