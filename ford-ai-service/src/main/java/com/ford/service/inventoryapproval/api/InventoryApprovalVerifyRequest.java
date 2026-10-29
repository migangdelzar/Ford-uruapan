package com.ford.service.inventoryapproval.api;

import com.ford.service.inventoryapproval.domain.InventoryOutcome;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record InventoryApprovalVerifyRequest(@NotNull InventoryOutcome outcome, @Min(0) Integer verifiedCount,
                                             @Min(0) long expectedVersion) { }
