package org.teraschool.product.api;

/** 产品域稳定错误码；不暴露数据库约束名与内部异常。 */
public class ProductConflict extends RuntimeException {
  private final String code;

  public ProductConflict(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
