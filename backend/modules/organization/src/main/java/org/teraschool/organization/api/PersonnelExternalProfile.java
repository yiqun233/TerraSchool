package org.teraschool.organization.api;

import java.util.UUID;

/** 外部通讯标识属于档案资料，不构成登录映射或权限。 */
public interface PersonnelExternalProfile {
  record ExternalIdentifiers(
      String alternateName,
      String legacyNumber,
      String qq,
      String wechatAccount,
      String wechatOpenId,
      String workWechatUserId,
      String dingTalkUserId,
      String meetingUserId,
      String telemarketingUserId,
      String seatSystemType,
      String seatNumber,
      String seatExtension,
      String teachingItemCode) {
    @Override
    public String toString() {
      return "ExternalIdentifiers[redacted]";
    }
  }

  record ExternalProfileView(
      UUID employeeId, UUID schoolId, long version, ExternalIdentifiers data) {}

  record UpdateExternalProfile(Long expectedVersion, ExternalIdentifiers data) {
    @Override
    public String toString() {
      return "UpdateExternalProfile[expectedVersion=" + expectedVersion + ", data=<redacted>]";
    }
  }

  ExternalProfileView getExternalProfile(UUID employeeId);

  ExternalProfileView updateExternalProfile(UUID employeeId, UpdateExternalProfile input);
}
