package com.ford.service.inventoryapproval.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InventoryApprovalApproveRequest(@Min(0) long expectedApprovalVersion,
                                              @Min(0) long expectedConversationVersion,
                                              @NotBlank @Size(max = 1000) String exactText) { }
