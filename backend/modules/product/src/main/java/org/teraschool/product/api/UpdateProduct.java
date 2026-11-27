package org.teraschool.product.api;

/** 修改产品；结算模式默认值变化不影响已发布的价格版本。 */
public record UpdateProduct(
    Long expectedVersion, String name, Integer settlementMode, Boolean active, Boolean oneOnOne) {

  /** 兼容旧调用：不修改一对一标记。 */
  public UpdateProduct(Long expectedVersion, String name, Integer settlementMode, Boolean active) {
    this(expectedVersion, name, settlementMode, active, null);
  }
}
