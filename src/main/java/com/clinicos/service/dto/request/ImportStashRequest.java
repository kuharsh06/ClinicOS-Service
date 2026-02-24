package com.clinicos.service.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportStashRequest {

    @NotBlank(message = "Source queue ID is required")
    private String sourceQueueId;

    private List<String> entryIds;  // specific entries (null = all)
}
