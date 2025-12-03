package io.github.cyfko.example;

import io.github.cyfko.typeindex.TypeKey;

@TypeKey(value = "#1")
public class Address {
    private String street;
    private String city;
    private String zipCode;
    private String country;
}