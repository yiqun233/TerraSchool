package org.teraschool.enrollment.api;

import java.util.UUID;

/**
 * 登记报名。
 *
 * <p>只提交学员与已发布的价格版本；单价、课时与商品名称由服务端从价格快照读取，不接受调用方传入的金额。 {@code enrolledOn} 是业务日期，用于校验价格版本在当天有效。
 */
public record CreateEnrollment(
    UUID requestId, UUID studentId, UUID priceVersionId, String enrolledOn) {}
