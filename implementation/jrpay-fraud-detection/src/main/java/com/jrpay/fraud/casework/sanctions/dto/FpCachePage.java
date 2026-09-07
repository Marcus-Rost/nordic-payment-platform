package com.jrpay.fraud.casework.sanctions.dto;

import java.util.List;

public record FpCachePage(List<FpCacheEntryDto> items, Page page) {

    public record Page(int number, int size, long totalElements, int totalPages) {}
}
