package org.teraschool.admissions.api;

import java.util.UUID;

public interface CustomerCommands {
  record CustomerCreate(
      UUID requestId,
      UUID schoolId,
      String name,
      String phone,
      UUID channelId,
      UUID confirmationToken) {}

  record CustomerCreateResult(String status, Customer customer, UUID confirmationToken) {}

  record CustomerPhone(String phone) {}

  CustomerCreateResult create(CustomerCreate command);

  /** 对请求身份记取消标记，阻止在途或未来相同请求创建。 */
  CustomerCreateResult cancel(CustomerCreate command);

  CustomerPhone revealPhone(UUID id);
}
