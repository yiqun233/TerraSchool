package org.teraschool.server;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.teraschool.identity.api.*;
import org.teraschool.platform.api.*;

/** 只记录明确的组织修改路由；不读取请求正文、原始路径、查询参数或外部追踪头。 */
@Component
class RejectedRequestAudit {
  private final CurrentActor actors;
  private final FailureAudit failures;

  RejectedRequestAudit(CurrentActor actors, FailureAudit failures) {
    this.actors = actors;
    this.failures = failures;
  }

  void record(HttpServletRequest request, String code) {
    if (!request.getMethod().equals("POST")
        && !(request.getMethod().equals("GET")
            && request
                .getServletPath()
                .matches("/api/organization/employees/[^/]+/attachments/[^/]+"))) return;
    String path = request.getServletPath();
    UUID resource = null;
    FailureAudit.Action action;
    if (path.equals("/api/employee/me/email-config"))
      action = FailureAudit.Action.EMPLOYEE_EMAIL_UPDATE;
    else if (path.equals("/api/organization/units"))
      action = FailureAudit.Action.ORGANIZATION_CREATE;
    else if (path.matches("/api/organization/units/[^/]+/rename")) {
      action = FailureAudit.Action.ORGANIZATION_RENAME;
      try {
        resource =
            UUID.fromString(
                path.substring(
                    "/api/organization/units/".length(), path.length() - "/rename".length()));
      } catch (IllegalArgumentException invalid) {
        /* 不保存无效路径文本。 */
      }
    } else if (path.matches("/api/authorization/accounts/[^/]+/replace")) {
      action = FailureAudit.Action.AUTHORIZATION_REPLACE;
      try {
        resource =
            UUID.fromString(
                path.substring(
                    "/api/authorization/accounts/".length(), path.length() - "/replace".length()));
      } catch (IllegalArgumentException invalid) {
        /* 不保存原始路径。 */
      }
    } else if (path.equals("/api/authorization/role-templates")) {
      action = FailureAudit.Action.ROLE_TEMPLATE_CREATE;
    } else if (path.matches("/api/authorization/role-templates/[^/]+/replace")) {
      action = FailureAudit.Action.ROLE_TEMPLATE_REPLACE;
      try {
        resource =
            UUID.fromString(
                path.substring(
                    "/api/authorization/role-templates/".length(),
                    path.length() - "/replace".length()));
      } catch (IllegalArgumentException invalid) {
        /* 不保存原始路径。 */
      }
    } else if (path.equals("/api/authorization/account-lifecycle")) {
      action = FailureAudit.Action.ACCOUNT_CREATE;
    } else if (path.matches(
        "/api/authorization/account-lifecycle/[^/]+/(status|reset-password|revoke-sessions)")) {
      String prefix = "/api/authorization/account-lifecycle/";
      int end = path.indexOf('/', prefix.length());
      try {
        resource = UUID.fromString(path.substring(prefix.length(), end));
      } catch (IllegalArgumentException invalid) {
        /* 不保存输入路径。 */
      }
      String operation = path.substring(end + 1);
      action =
          switch (operation) {
            case "status" -> FailureAudit.Action.ACCOUNT_STATUS;
            case "reset-password" -> FailureAudit.Action.ACCOUNT_PASSWORD_RESET;
            default -> FailureAudit.Action.ACCOUNT_SESSIONS_REVOKE;
          };
    } else if (path.matches("/api/organization/employees/[^/]+/attachments(/[^/]+(/remove)?)?")) {
      String[] parts = path.split("/");
      boolean read = request.getMethod().equals("GET");
      action =
          read
              ? FailureAudit.Action.PERSONNEL_ATTACHMENT_READ
              : parts.length > 6
                  ? FailureAudit.Action.PERSONNEL_ATTACHMENT_REMOVE
                  : FailureAudit.Action.PERSONNEL_ATTACHMENT_CREATE;
      try {
        resource = UUID.fromString(parts.length > 6 ? parts[6] : parts[4]);
      } catch (IllegalArgumentException invalid) {
        /* 不记录原路径。 */
      }
    } else if (path.matches("/api/organization/employees/[^/]+/external-identifiers")) {
      action = FailureAudit.Action.PERSONNEL_EXTERNAL_UPDATE;
      try {
        resource = UUID.fromString(path.split("/")[4]);
      } catch (IllegalArgumentException invalid) {
        /* 不记录原路径。 */
      }
    } else if (path.matches("/api/organization/employees/[^/]+/personnel")) {
      action = FailureAudit.Action.PERSONNEL_UPDATE;
      try {
        resource = UUID.fromString(path.split("/")[4]);
      } catch (IllegalArgumentException invalid) {
        /* 不记录原路径。 */
      }
    } else if (path.matches("/api/organization/employees/[^/]+/account-link")) {
      action = FailureAudit.Action.EMPLOYEE_ACCOUNT_BIND;
      try {
        resource = UUID.fromString(path.split("/")[4]);
      } catch (IllegalArgumentException invalid) {
        /* 不记录原路径。 */
      }
    } else if (path.matches("/api/organization/teachers/[^/]+/qualification")) {
      action = FailureAudit.Action.TEACHER_QUALIFICATION_UPDATE;
      try {
        resource = UUID.fromString(path.split("/")[4]);
      } catch (IllegalArgumentException invalid) {
        /* 不记录原路径。 */
      }
    } else if (path.matches("/api/organization/schools/[^/]+/update")) {
      action = FailureAudit.Action.ORGANIZATION_SCHOOL_UPDATE;
      try {
        resource = UUID.fromString(path.split("/")[4]);
      } catch (IllegalArgumentException invalid) {
        /* 不记录原路径。 */
      }
    } else if (path.equals("/api/organization/schools")) {
      action = FailureAudit.Action.ORGANIZATION_SCHOOL_CREATE;
    } else if (path.matches(
        "/api/organization/resources/(EMPLOYEE|TEACHER|ROOM)(/[^/]+/update)?")) {
      String tail = path.substring("/api/organization/resources/".length());
      String[] parts = tail.split("/");
      boolean update = parts.length == 3;
      if (update)
        try {
          resource = UUID.fromString(parts[1]);
        } catch (IllegalArgumentException invalid) {
          /* 不保存原始路径。 */
        }
      action =
          switch (parts[0]) {
            case "EMPLOYEE" ->
                update ? FailureAudit.Action.EMPLOYEE_UPDATE : FailureAudit.Action.EMPLOYEE_CREATE;
            case "TEACHER" ->
                update ? FailureAudit.Action.TEACHER_UPDATE : FailureAudit.Action.TEACHER_CREATE;
            default -> update ? FailureAudit.Action.ROOM_UPDATE : FailureAudit.Action.ROOM_CREATE;
          };
    } else return;
    AuditSubject subject = null;
    try {
      subject = actors.auditSubject().orElse(null);
    } catch (RuntimeException unavailable) {
      /* 独立记录未知身份。 */
    }
    failures.append(
        new FailureAudit.Entry(
            UUID.randomUUID(),
            RequestTrace.id(),
            subject == null ? null : subject.tenantId(),
            subject == null ? null : subject.accountId(),
            action,
            RequestTrace.Source.HTTP,
            FailureAudit.Result.REJECTED,
            code,
            resource));
  }
}
