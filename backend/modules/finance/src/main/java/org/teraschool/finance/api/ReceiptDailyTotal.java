package org.teraschool.finance.api;

/** 一日内按收款方式与单据状态分组的收款原额汇总。 */
public record ReceiptDailyTotal(
    String day, String method, String status, long receiptCount, String grossAmount) {}
