package com.jrpay.psd2.customer.schedules.dto;

import java.util.List;

public record SchedulePage(List<ScheduleDto> items, Page page) {

    public record Page(int number, int size, long totalElements, int totalPages) {}
}
