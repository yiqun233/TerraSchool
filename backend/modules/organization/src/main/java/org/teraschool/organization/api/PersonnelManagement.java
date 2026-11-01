package org.teraschool.organization.api;

import java.util.List;
import java.util.UUID;

/** 敏感人事档案独立授权；字段是固定契约，不接受自由字段。 */
public interface PersonnelManagement {
  record Child(String name, String gender, String birthday) {
    @Override
    public String toString() {
      return "Child[redacted]";
    }
  }

  record ContributionPeriod(String type, String startMonth, String amount) {}

  record PersonnelData(
      String employeeNumber,
      String employeeType,
      String post,
      String jobStatus,
      String entryDate,
      String leaveDate,
      String contractStart,
      String contractEnd,
      String probationStart,
      String probationEnd,
      String gender,
      String birthday,
      String idNumber,
      String personalPhone,
      String officePhone,
      String personalEmail,
      String enterpriseEmail,
      String bankName,
      String bankAccount,
      String emergencyContactName,
      String emergencyContactRelation,
      String emergencyContactPhone,
      String education,
      String graduateSchool,
      String major,
      String householdCity,
      String householdType,
      String householdAddress,
      String residenceCity,
      String residenceStreet,
      String residenceAddress,
      String maritalStatus,
      String politicalStatus,
      String housingFundAccount,
      String teacherCertificateStatus,
      String baseSalary,
      String employerSocialInsurance,
      String employerHousingFund,
      String memo,
      List<Child> children,
      List<ContributionPeriod> contributionPeriods) {
    @Override
    public String toString() {
      return "PersonnelData[redacted]";
    }
  }

  record PersonnelView(UUID employeeId, UUID schoolId, long version, PersonnelData data) {}

  record UpdatePersonnel(Long expectedVersion, PersonnelData data) {
    @Override
    public String toString() {
      return "UpdatePersonnel[expectedVersion=" + expectedVersion + ", data=<redacted>]";
    }
  }

  PersonnelView getPersonnel(UUID employeeId);

  PersonnelView updatePersonnel(UUID employeeId, UpdatePersonnel input);
}
