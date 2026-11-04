package org.teraschool.admissions.api;

import java.util.UUID;

/** 列表只返回脱敏电话；本人之外的客户不在本批可见范围。 */
public record Customer(
    UUID id, UUID schoolId, String name, String maskedPhone, UUID channelId, long version) {}
