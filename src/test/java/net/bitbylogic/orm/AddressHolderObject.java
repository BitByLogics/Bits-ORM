package net.bitbylogic.orm;

import net.bitbylogic.orm.annotation.Column;
import net.bitbylogic.orm.data.BormObject;

/**
 * Uses the same subClass'd type (Address) twice, which used to be impossible: it collided both
 * at the SQL column level and at the constructor-parameter-matching level.
 */
public class AddressHolderObject extends BormObject {

    @Column(primaryKey = true)
    private String id;

    @Column(subClass = true)
    private Address billingAddress;

    @Column(subClass = true)
    private Address shippingAddress;

    public AddressHolderObject() {
    }

    public AddressHolderObject(String id, String billingAddress_name, String billingAddress_city,
                                String shippingAddress_name, String shippingAddress_city) {
        this.id = id;
        this.billingAddress = new Address(billingAddress_name, billingAddress_city);
        this.shippingAddress = new Address(shippingAddress_name, shippingAddress_city);
    }

    public String getId() {
        return id;
    }

    public Address getBillingAddress() {
        return billingAddress;
    }

    public Address getShippingAddress() {
        return shippingAddress;
    }

}
