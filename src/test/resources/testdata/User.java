package io.github.cyfko.example;

import io.github.cyfko.typeindex.TypeKey;

@TypeKey(value = "my-key")
public class User {
    
    private String firstName;
    
    private String lastName;

    private Address address;
}