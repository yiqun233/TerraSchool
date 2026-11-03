package org.teraschool.teaching.api;

/**
 * 期初已消耗课时与金额（迁移旧库用）。
 *
 * <p>它没有对应课次，但会影响"可退课时"和"剩余课时"，因此写进消费表并标记来源； 同一报名只允许一条期初消耗。
 */
public record OpeningConsumption(String hours, String amount, String reason, String occurredOn) {}
