package com.jrpay.ledger.admin.dto;

import java.util.List;

public record SearchResult(
        List<AdminAccountSummary> items,
        int page,
        int size,
        int totalElements,
        boolean truncated
) {}
