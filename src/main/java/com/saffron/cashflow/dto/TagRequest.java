package com.saffron.cashflow.dto;

import jakarta.validation.constraints.Size;

/** Create / update a tag. All fields optional on update; name required on create. */
public record TagRequest(
        @Size(max = 64) String name,
        @Size(max = 9) String color,
        @Size(max = 200) String description) {}
