package org.teraschool.funds.api;

import java.util.UUID;

/** 学员账户：总额来自带符号流水，冻结来自有效退款准备，可用 = 总额 − 冻结。 */
public record StudentBalance(
    UUID studentId,
    String studentName,
    String total,
    String reserved,
    String allocated,
    String available) {}
