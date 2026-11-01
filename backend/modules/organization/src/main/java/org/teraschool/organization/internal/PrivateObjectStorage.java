package org.teraschool.organization.internal;

/** 仅供人事私有附件；实现负责不暴露对象地址。 */
interface PrivateObjectStorage {
  boolean configured();

  void put(String key, byte[] content);

  byte[] get(String key);

  void delete(String key);
}
