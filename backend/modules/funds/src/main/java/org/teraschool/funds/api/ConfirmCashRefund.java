package org.teraschool.funds.api;

/** 确认已向家长实际支付；必须提供凭据号并携带退款单版本。 */
public record ConfirmCashRefund(Long expectedVersion, String reference) {}
