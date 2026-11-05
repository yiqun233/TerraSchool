package org.teraschool.finance.api;

/** 单一学校和业务日的运营概况；收款与作废金额分别显示，不扣退款或互相抵消。 */
public record DailyOverview(
    String day,
    long enrollmentCount,
    long confirmedReceiptCount,
    String confirmedReceiptAmount,
    long voidedReceiptCount,
    String voidedReceiptAmount,
    long lessonCount,
    long closedLessonCount,
    long attendanceRecordCount) {}
