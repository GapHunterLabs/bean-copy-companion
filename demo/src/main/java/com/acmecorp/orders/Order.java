package com.acmecorp.orders;

/**
 * Demo entity for Bean Copy Companion screenshots. Real-world shape on
 * purpose: a plain Java entity you'd copy into a Kotlin DTO before
 * sending it over an API boundary -- exactly the use case the closest
 * competitor (Simple Object Copy, JetBrains Marketplace id 18151) gets
 * real 1-star reviews for not supporting.
 */
public class Order {

    private long id;
    private String customerName;
    private double total;

    public long getId() {
        return id;
    }

    public String getCustomerName() {
        return customerName;
    }

    public double getTotal() {
        return total;
    }
}
