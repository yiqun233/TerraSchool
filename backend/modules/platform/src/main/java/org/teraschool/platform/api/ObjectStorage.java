package org.teraschool.platform.api;

/**
 * 私有对象存储的最小契约：调用方只持有业务键，不接触对象地址或存储凭据。
 *
 * <p>当前实现为 S3 兼容接口；未配置时 {@link #configured()} 返回 false，调用方应拒绝 依赖存储的业务动作，而不是静默写到本地目录。
 */
public interface ObjectStorage {
  boolean configured();

  void put(String key, byte[] content, String contentType);

  byte[] get(String key);

  void delete(String key);
}
