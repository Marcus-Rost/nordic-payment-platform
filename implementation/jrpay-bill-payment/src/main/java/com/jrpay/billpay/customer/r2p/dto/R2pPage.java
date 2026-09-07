package com.jrpay.billpay.customer.r2p.dto;

import java.util.List;

public record R2pPage(
        List<R2pSummary> items,
        Page page
) {

    public record Page(int number, int size, long totalElements, int totalPages) {}
}
