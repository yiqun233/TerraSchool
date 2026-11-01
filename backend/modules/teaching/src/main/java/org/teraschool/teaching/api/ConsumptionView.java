package org.teraschool.teaching.api;

import java.util.UUID;

/** 课次消费：关闭课次时固化，金额与课时为十进制字符串。 */
public record ConsumptionView(
    UUID id,
    UUID enrollmentId,
    UUID lessonId,
    String lessonDate,
    String hours,
    String amount,
    int settlementMode,
    String priceSnapshot,
    String hoursSnapshot) {}
