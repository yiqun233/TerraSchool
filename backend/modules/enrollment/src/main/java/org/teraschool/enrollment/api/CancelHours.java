package org.teraschool.enrollment.api;

/** 退课时：本次退多少课时与原因；金额由服务端按冻结快照计算。 */
public record CancelHours(String hours, String reason) {}
