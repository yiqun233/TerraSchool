package org.teraschool.teaching.api;

import java.util.List;
import java.util.UUID;

/**
 * 执行一条链式调课方案。服务端在一个事务内按顺序落库，任何一步失败整体回滚。
 *
 * @param requestId 幂等键
 * @param reason 调课原因（必填，≤200 字）
 * @param moves 方案里的步骤（与搜索结果同序）
 */
public record ExecuteSwapScheme(UUID requestId, String reason, List<SwapSchemePlan.Move> moves) {}
