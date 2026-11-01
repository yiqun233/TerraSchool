package org.teraschool.funds.api;

import java.util.UUID;

/**
 * 退课时并退到账户：本次退多少课时、原因与幂等身份。
 *
 * <p>金额由服务端按报名冻结快照计算；退课时后自动把分配调整到新的净应收，差额留在学员账户。
 */
public record CancelHoursAndReturnCommand(UUID requestId, String hours, String reason) {}
