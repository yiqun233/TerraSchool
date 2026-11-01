package org.teraschool.organization.api;

import java.util.UUID;

/** 教师资质简介；与教师基本资料共用乐观版本。 */
public interface TeacherQualifications {
  record QualificationView(UUID teacherId, UUID schoolId, String qualification, long version) {
    @Override
    public String toString() {
      return "QualificationView[teacherId="
          + teacherId
          + ", qualification=<redacted>, version="
          + version
          + "]";
    }
  }

  record UpdateQualification(Long expectedVersion, String qualification) {
    @Override
    public String toString() {
      return "UpdateQualification[expectedVersion="
          + expectedVersion
          + ", qualification=<redacted>]";
    }
  }

  QualificationView getQualification(UUID teacherId);

  QualificationView updateQualification(UUID teacherId, UpdateQualification input);
}
