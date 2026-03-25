package com.example;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.validation.constraints.NotBlank;

@Entity
@Table(name = "my_entity")
public class MyEntity {
    @Id
    @GeneratedValue
    private Long id;

    @Column
    @NotBlank
    private String name;
}
