package org.teraschool.organization.api;

import java.util.List;
import java.util.UUID;

/** 人事档案私有附件；对象地址和存储凭据不暴露给调用方。 */
public interface PersonnelAttachments {
  record AttachmentView(UUID id, UUID employeeId, String name, long size, long version) {}

  record UploadAttachment(UUID requestId, String name, byte[] content) {
    @Override
    public String toString() {
      return "UploadAttachment[requestId=" + requestId + ", content=<redacted>]";
    }
  }

  record AttachmentContent(String name, byte[] content) {
    @Override
    public String toString() {
      return "AttachmentContent[content=<redacted>]";
    }
  }

  record RemoveAttachment(Long expectedVersion) {}

  List<AttachmentView> listAttachments(UUID employeeId);

  AttachmentView createAttachment(UUID employeeId, UploadAttachment input);

  AttachmentContent readAttachment(UUID employeeId, UUID attachmentId);

  void removeAttachment(UUID employeeId, UUID attachmentId, RemoveAttachment input);
}
