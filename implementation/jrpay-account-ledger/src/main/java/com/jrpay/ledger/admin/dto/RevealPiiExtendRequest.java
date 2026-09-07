package com.jrpay.ledger.admin.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RevealPiiExtendRequest(@NotNull UUID revealId) {}
