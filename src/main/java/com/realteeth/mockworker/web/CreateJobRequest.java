package com.realteeth.mockworker.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateJobRequest(
        @NotBlank @Size(max = 2048) String imageUrl
) {}
