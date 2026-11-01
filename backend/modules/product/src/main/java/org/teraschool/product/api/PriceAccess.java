package org.teraschool.product.api;

import java.util.UUID;

/**
 * 他域读取价格版本的最小契约。
 *
 * <p>调用方自行完成授权；本契约只用租户 RLS 限制可见范围，并返回报名需要冻结的快照字段。
 */
public interface PriceAccess {
  PriceSnapshot require(UUID priceVersionId);
}
