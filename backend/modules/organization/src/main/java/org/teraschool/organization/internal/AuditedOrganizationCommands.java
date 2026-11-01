package org.teraschool.organization.internal;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.identity.api.*;
import org.teraschool.organization.api.*;
import org.teraschool.platform.api.*;

/** 围绕完整操作记录失败，HTTP和直接调用都走此公开契约；成功审计仍在业务事务内。 */
@Service
@Primary
public class AuditedOrganizationCommands
    implements OrganizationCommands,
        SchoolManagement,
        ResourceManagement,
        TeacherQualifications,
        EmployeeAccountLinks,
        PersonnelManagement,
        PersonnelAttachments,
        EmployeeEmailConfiguration,
        PersonnelExternalProfile {
  private final OrganizationMaintenance target;
  private final CurrentActor actors;
  private final FailureAudit failures;
  private final SchoolMaintenance schools;
  private final Resources resources;
  private final TeacherQualificationService qualifications;
  private final EmployeeAccountLinkService links;
  private final PersonnelService personnel;
  private final PersonnelAttachmentService attachments;
  private final EmployeeEmailService email;
  private final PersonnelExternalService external;

  public AuditedOrganizationCommands(
      OrganizationMaintenance target,
      CurrentActor actors,
      FailureAudit failures,
      SchoolMaintenance schools,
      Resources resources,
      TeacherQualificationService qualifications,
      EmployeeAccountLinkService links,
      PersonnelService personnel,
      PersonnelAttachmentService attachments,
      EmployeeEmailService email,
      PersonnelExternalService external) {
    this.target = target;
    this.actors = actors;
    this.failures = failures;
    this.schools = schools;
    this.resources = resources;
    this.qualifications = qualifications;
    this.links = links;
    this.personnel = personnel;
    this.attachments = attachments;
    this.email = email;
    this.external = external;
  }

  public EmailConfigurationView getMyEmailConfiguration() {
    return email.get();
  }

  public EmailConfigurationView changeMyEmailConfiguration(ChangeEmailConfiguration input) {
    return invoke(FailureAudit.Action.EMPLOYEE_EMAIL_UPDATE, null, () -> email.change(input));
  }

  public ExternalProfileView getExternalProfile(UUID id) {
    return external.get(id);
  }

  public ExternalProfileView updateExternalProfile(UUID id, UpdateExternalProfile input) {
    return invoke(
        FailureAudit.Action.PERSONNEL_EXTERNAL_UPDATE, id, () -> external.update(id, input));
  }

  public java.util.List<AttachmentView> listAttachments(UUID employee) {
    return attachments.list(employee);
  }

  public AttachmentView createAttachment(UUID employee, UploadAttachment input) {
    return invoke(
        FailureAudit.Action.PERSONNEL_ATTACHMENT_CREATE,
        employee,
        () -> attachments.create(employee, input));
  }

  public AttachmentContent readAttachment(UUID employee, UUID attachment) {
    return invoke(
        FailureAudit.Action.PERSONNEL_ATTACHMENT_READ,
        attachment,
        () -> attachments.read(employee, attachment));
  }

  public void removeAttachment(UUID employee, UUID attachment, RemoveAttachment input) {
    invoke(
        FailureAudit.Action.PERSONNEL_ATTACHMENT_REMOVE,
        attachment,
        () -> {
          attachments.remove(employee, attachment, input);
          return null;
        });
  }

  public PersonnelView getPersonnel(UUID id) {
    return personnel.get(id);
  }

  public PersonnelView updatePersonnel(UUID id, UpdatePersonnel input) {
    return invoke(FailureAudit.Action.PERSONNEL_UPDATE, id, () -> personnel.update(id, input));
  }

  public AccountLink getLink(UUID id) {
    return links.get(id);
  }

  public AccountLink changeLink(UUID id, ChangeAccountLink input) {
    return invoke(FailureAudit.Action.EMPLOYEE_ACCOUNT_BIND, id, () -> links.change(id, input));
  }

  public QualificationView getQualification(UUID id) {
    return qualifications.get(id);
  }

  public QualificationView updateQualification(UUID id, UpdateQualification input) {
    return invoke(
        FailureAudit.Action.TEACHER_QUALIFICATION_UPDATE,
        id,
        () -> qualifications.update(id, input));
  }

  @Override
  public OrganizationUnit createChild(CreateOrganizationUnit command) {
    return invoke(FailureAudit.Action.ORGANIZATION_CREATE, null, () -> target.createChild(command));
  }

  @Override
  public OrganizationUnit rename(UUID id, RenameOrganizationUnit command) {
    return invoke(FailureAudit.Action.ORGANIZATION_RENAME, id, () -> target.rename(id, command));
  }

  public boolean canCreate() {
    return schools.canCreate();
  }

  public OrganizationUnit createSchool(SchoolManagement.CreateSchool input) {
    return invoke(
        FailureAudit.Action.ORGANIZATION_SCHOOL_CREATE, null, () -> schools.createSchool(input));
  }

  public SchoolView getSchool(UUID id) {
    return schools.getSchool(id);
  }

  public SchoolView updateSchool(UUID id, UpdateSchool input) {
    return invoke(
        FailureAudit.Action.ORGANIZATION_SCHOOL_UPDATE, id, () -> schools.updateSchool(id, input));
  }

  public ResourceManagement.ResourceCapabilities capabilities(ResourceManagement.Kind kind) {
    return resources.capabilities(kind);
  }

  public ResourceManagement.ResourcePage list(ResourceManagement.Kind kind, int page, int size) {
    return resources.list(kind, page, size);
  }

  public java.util.List<ResourceManagement.ResourceOption> options(
      ResourceManagement.Kind kind, String type, UUID school, String search) {
    return resources.options(kind, type, school, search);
  }

  private FailureAudit.Action resourceAction(ResourceManagement.Kind kind, boolean create) {
    if (kind == null) throw new IllegalArgumentException("需要资源类型");
    return switch (kind) {
      case EMPLOYEE ->
          create ? FailureAudit.Action.EMPLOYEE_CREATE : FailureAudit.Action.EMPLOYEE_UPDATE;
      case TEACHER ->
          create ? FailureAudit.Action.TEACHER_CREATE : FailureAudit.Action.TEACHER_UPDATE;
      case ROOM -> create ? FailureAudit.Action.ROOM_CREATE : FailureAudit.Action.ROOM_UPDATE;
    };
  }

  public ResourceManagement.ResourceView create(
      ResourceManagement.Kind kind, ResourceManagement.CreateResource input) {
    return invoke(resourceAction(kind, true), null, () -> resources.create(kind, input));
  }

  public ResourceManagement.ResourceView update(
      ResourceManagement.Kind kind, UUID id, ResourceManagement.UpdateResource input) {
    return invoke(resourceAction(kind, false), id, () -> resources.update(kind, id, input));
  }

  private <T> T invoke(FailureAudit.Action action, UUID resource, Supplier<T> work) {
    try (var scope = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      try {
        T result = work.get();
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()
            && org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()) {
          AuditSubject subject = subject();
          var trace = RequestTrace.id();
          var source = RequestTrace.source();
          var attempt = UUID.randomUUID();
          UUID affected =
              resource == null && result instanceof OrganizationUnit unit
                  ? unit.id()
                  : resource == null && result instanceof ResourceManagement.ResourceView item
                      ? item.id()
                      : resource;
          org.springframework.transaction.support.TransactionSynchronizationManager
              .registerSynchronization(
                  new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                      if (status == STATUS_COMMITTED) return;
                      String code =
                          status == STATUS_ROLLED_BACK
                              ? "TRANSACTION_ROLLED_BACK"
                              : "TRANSACTION_OUTCOME_UNKNOWN";
                      try {
                        failures.append(
                            new FailureAudit.Entry(
                                attempt,
                                trace,
                                subject == null ? null : subject.tenantId(),
                                subject == null ? null : subject.accountId(),
                                action,
                                source,
                                FailureAudit.Result.FAILED,
                                code,
                                affected));
                      } catch (AuditUnavailable unavailable) {
                        org.slf4j.LoggerFactory.getLogger(getClass())
                            .error("AUDIT_AFTER_COMPLETION_FAILED trace={} code={}", trace, code);
                      }
                    }
                  });
        }
        return result;
      } catch (RuntimeException error) {
        AuditSubject subject = null;
        try {
          subject = actors.auditSubject().orElse(null);
        } catch (RuntimeException unavailable) {
          /* 身份库故障时只记未知主体，不相信输入补全。 */
        }
        String code =
            error instanceof AccessDeniedException
                ? "ACCESS_DENIED"
                : error instanceof IllegalArgumentException
                    ? "INVALID_INPUT"
                    : error instanceof OrganizationConflict conflict
                        ? conflict.code()
                        : "OPERATION_FAILED";
        failures.append(
            new FailureAudit.Entry(
                UUID.randomUUID(),
                RequestTrace.id(),
                subject == null ? null : subject.tenantId(),
                subject == null ? null : subject.accountId(),
                action,
                RequestTrace.source(),
                code.equals("OPERATION_FAILED")
                    ? FailureAudit.Result.FAILED
                    : FailureAudit.Result.REJECTED,
                code,
                resource));
        throw error;
      }
    }
  }

  private AuditSubject subject() {
    try {
      return actors.auditSubject().orElse(null);
    } catch (RuntimeException unavailable) {
      return null;
    }
  }
}
