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
            && (request
                    .getServletPath()
                    .matches("/api/organization/employees/[^/]+/attachments/[^/]+")
                || request.getServletPath().matches("/api/archive/students/[^/]+")
                || request.getServletPath().matches("/api/admissions/customers/[^/]+/followups")
                || request.getServletPath().matches("/api/admissions/customers/[^/]+/merges")
                || request.getServletPath().matches("/api/admissions/customers/[^/]+/student-link")
                || request.getServletPath().equals("/api/admissions/customers/pool")))) return;
    String path = request.getServletPath();
    UUID resource = null;
    FailureAudit.Action action;
    if (path.equals("/api/admissions/customers/pool") && request.getMethod().equals("GET"))
      action = FailureAudit.Action.CUSTOMER_POOL_READ;
    else if (request.getMethod().equals("GET")
        && path.matches("/api/admissions/customers/[^/]+/merges")) {
      action = FailureAudit.Action.CUSTOMER_MERGE;
      try {
        resource = UUID.fromString(path.split("/")[4]);
      } catch (IllegalArgumentException invalid) {
        /* 不记录原路径 */
      }
    } else if (path.matches("/api/admissions/customers/[^/]+/merge/reverse")) {
      action = FailureAudit.Action.CUSTOMER_MERGE_REVERSE;
      try {
        resource = UUID.fromString(path.split("/")[4]);
      } catch (IllegalArgumentException invalid) {
        /* 不记录原路径 */
      }
    } else if (path.matches("/api/admissions/customers/[^/]+/merge")) {
      action = FailureAudit.Action.CUSTOMER_MERGE;
      try {
        resource = UUID.fromString(path.split("/")[4]);
      } catch (IllegalArgumentException invalid) {
        /* 不记录原路径 */
      }
    } else if (path.matches("/api/admissions/customers/[^/]+/convert-to-student")) {
      action = FailureAudit.Action.CUSTOMER_CONVERT_TO_STUDENT;
      try {
        resource = UUID.fromString(path.split("/")[4]);
      } catch (IllegalArgumentException invalid) {
        /* 不记录原路径 */
      }
    } else if (request.getMethod().equals("GET")
        && path.matches("/api/admissions/customers/[^/]+/student-link")) {
      action = FailureAudit.Action.CUSTOMER_CONVERT_TO_STUDENT;
      try {
        resource = UUID.fromString(path.split("/")[4]);
      } catch (IllegalArgumentException invalid) {
        /* 不记录原路径 */
      }
    } else if (path.equals("/api/admissions/customers"))
      action = FailureAudit.Action.CUSTOMER_CREATE;
    else if (path.equals("/api/admissions/customers/cancel"))
      action = FailureAudit.Action.CUSTOMER_CANCEL;
    else if (path.matches("/api/admissions/customers/[^/]+/release-to-pool")) {
      action = FailureAudit.Action.CUSTOMER_POOL_RELEASE;
      try {
        resource = UUID.fromString(path.split("/")[4]);
      } catch (IllegalArgumentException invalid) {
        /* 不记录原路径 */
      }
    } else if (path.matches("/api/admissions/customers/[^/]+/claim")) {
      action = FailureAudit.Action.CUSTOMER_POOL_CLAIM;
      try {
        resource = UUID.fromString(path.split("/")[4]);
      } catch (IllegalArgumentException invalid) {
        /* 不记录原路径 */
      }
    } else if (path.matches("/api/admissions/customers/[^/]+/assign-owner")) {
      action = FailureAudit.Action.CUSTOMER_ASSIGN;
      try {
        resource = UUID.fromString(path.split("/")[4]);
      } catch (IllegalArgumentException invalid) {
        /* 不记录原路径 */
      }
    } else if (path.matches("/api/admissions/customers/[^/]+/phone")) {
      action = FailureAudit.Action.CUSTOMER_PHONE_VIEW;
      try {
        resource = UUID.fromString(path.split("/")[4]);
      } catch (IllegalArgumentException invalid) {
        /* 不记录原路径 */
      }
    } else if (path.matches("/api/admissions/customers/[^/]+/followups")) {
      action =
          request.getMethod().equals("GET")
              ? FailureAudit.Action.CUSTOMER_FOLLOWUP_READ
              : FailureAudit.Action.CUSTOMER_FOLLOWUP_CREATE;
      try {
        resource = UUID.fromString(path.split("/")[4]);
      } catch (IllegalArgumentException invalid) {
        /* 不记录原路径 */
      }
    } else if (path.equals("/api/admissions/channels")) action = FailureAudit.Action.CHANNEL_CREATE;
    else if (path.matches("/api/admissions/channels/[^/]+/(update|address)")) {
      action = FailureAudit.Action.CHANNEL_UPDATE;
      try {
        resource = UUID.fromString(path.split("/")[4]);
      } catch (IllegalArgumentException invalid) {
        /* 不记录原路径 */
      }
    } else if (path.equals("/api/teaching/schedule/batch/preview"))
      action = FailureAudit.Action.SCHEDULE_PREVIEW;
    else if (path.equals("/api/teaching/schedule/batch/confirm"))
      action = FailureAudit.Action.SCHEDULE_CONFIRM;
    else if (path.matches("/api/teaching/classes/[^/]+/schedule/(preview|confirm)")) {
      action =
          path.endsWith("/confirm")
              ? FailureAudit.Action.SCHEDULE_CONFIRM
              : FailureAudit.Action.SCHEDULE_PREVIEW;
      try {
        resource =
            UUID.fromString(
                path.substring(
                    "/api/teaching/classes/".length(),
                    path.length()
                        - (path.endsWith("/confirm")
                            ? "/schedule/confirm".length()
                            : "/schedule/preview".length())));
      } catch (IllegalArgumentException invalid) {
        /* 不保存原始路径文本。 */
      }
    } else if (path.equals("/api/approval/policies")) action = FailureAudit.Action.APPROVAL_POLICY;
    else if (path.equals("/api/approval/requests")) action = FailureAudit.Action.APPROVAL_REQUEST;
    else if (path.matches("/api/approval/requests/[^/]+/decide")) {
      action = FailureAudit.Action.APPROVAL_DECIDE;
      try {
        resource =
            UUID.fromString(
                path.substring(
                    "/api/approval/requests/".length(), path.length() - "/decide".length()));
      } catch (IllegalArgumentException invalid) {
        /* 不保存原始路径文本。 */
      }
    } else if (path.matches("/api/approval/requests/[^/]+/withdraw")) {
      action = FailureAudit.Action.APPROVAL_REQUEST_WITHDRAW;
      try {
        resource =
            UUID.fromString(
                path.substring(
                    "/api/approval/requests/".length(), path.length() - "/withdraw".length()));
      } catch (IllegalArgumentException invalid) {
        /* 不保存原始路径文本。 */
      }
    } else if (path.matches("/api/approval/requests/[^/]+/expire")) {
      action = FailureAudit.Action.APPROVAL_REQUEST_EXPIRE;
      try {
        resource =
            UUID.fromString(
                path.substring(
                    "/api/approval/requests/".length(), path.length() - "/expire".length()));
      } catch (IllegalArgumentException invalid) {
        /* 不保存原始路径文本。 */
      }
    } else if (path.matches("/api/funds/enrollments/[^/]+/cancel-hours")) {
      action = FailureAudit.Action.REFUND_HOURS;
      try {
        resource =
            UUID.fromString(
                path.substring(
                    "/api/funds/enrollments/".length(), path.length() - "/cancel-hours".length()));
      } catch (IllegalArgumentException invalid) {
        /* 不保存原始路径文本。 */
      }
    } else if (path.matches("/api/funds/enrollments/[^/]+/refund-service-fee")) {
      action = FailureAudit.Action.REFUND_HOURS;
      try {
        resource =
            UUID.fromString(
                path.substring(
                    "/api/funds/enrollments/".length(),
                    path.length() - "/refund-service-fee".length()));
      } catch (IllegalArgumentException invalid) {
        /* 不保存原始路径文本。 */
      }
    } else if (path.matches("/api/funds/enrollments/[^/]+/service-fee-refunds/reverse")) {
      action = FailureAudit.Action.REFUND_HOURS;
      try {
        resource =
            UUID.fromString(
                path.substring(
                    "/api/funds/enrollments/".length(),
                    path.length() - "/service-fee-refunds/reverse".length()));
      } catch (IllegalArgumentException invalid) {
        /* 不保存原始路径文本。 */
      }
    } else if (path.equals("/api/funds/refunds")) action = FailureAudit.Action.REFUND_PREPARE;
    else if (path.matches("/api/funds/refunds/[^/]+/(confirm|cancel)")) {
      action =
          path.endsWith("/confirm")
              ? FailureAudit.Action.REFUND_PAID
              : FailureAudit.Action.REFUND_CANCEL;
      String suffix = path.substring(path.lastIndexOf('/'));
      try {
        resource =
            UUID.fromString(
                path.substring("/api/funds/refunds/".length(), path.length() - suffix.length()));
      } catch (IllegalArgumentException invalid) {
        /* 不保存原始路径文本。 */
      }
    } else if (path.equals("/api/teaching/classes")) action = FailureAudit.Action.CLASS_CREATE;
    else if (path.matches("/api/teaching/classes/[^/]+/close")) {
      action = FailureAudit.Action.CLASS_CLOSE;
      try {
        resource = UUID.fromString(path.split("/")[4]);
      } catch (IllegalArgumentException invalid) {
        /* 不记录原路径 */
      }
    } else if (path.matches("/api/teaching/classes/[^/]+/cancel")) {
      action = FailureAudit.Action.CLASS_CANCEL;
      try {
        resource = UUID.fromString(path.split("/")[4]);
      } catch (IllegalArgumentException invalid) {
        /* 不记录原路径 */
      }
    } else if (path.matches("/api/teaching/classes/[^/]+/members")) {
      action = FailureAudit.Action.CLASS_MEMBER_ADD;
      try {
        resource =
            UUID.fromString(
                path.substring(
                    "/api/teaching/classes/".length(), path.length() - "/members".length()));
      } catch (IllegalArgumentException invalid) {
        /* 不保存原始路径文本。 */
      }
    } else if (path.equals("/api/teaching/lessons")) action = FailureAudit.Action.LESSON_CREATE;
    else if (path.matches("/api/teaching/lessons/[^/]+/(publish|attendance|close)")) {
      action =
          path.endsWith("/publish")
              ? FailureAudit.Action.LESSON_PUBLISH
              : path.endsWith("/attendance")
                  ? FailureAudit.Action.ATTENDANCE_CONFIRM
                  : FailureAudit.Action.LESSON_CLOSE;
      String suffix = path.substring(path.lastIndexOf('/'));
      try {
        resource =
            UUID.fromString(
                path.substring("/api/teaching/lessons/".length(), path.length() - suffix.length()));
      } catch (IllegalArgumentException invalid) {
        /* 不保存原始路径文本。 */
      }
    } else if (path.equals("/api/enrollment/enrollments")
        || path.equals("/api/enrollment/enrollments/batch"))
      action = FailureAudit.Action.ENROLLMENT_CREATE;
    else if (path.matches("/api/enrollment/enrollments/[^/]+/approve")) {
      action = FailureAudit.Action.ENROLLMENT_APPROVE;
      try {
        resource =
            UUID.fromString(
                path.substring(
                    "/api/enrollment/enrollments/".length(), path.length() - "/approve".length()));
      } catch (IllegalArgumentException invalid) {
        /* 不保存原始路径文本。 */
      }
    } else if (path.equals("/api/funds/receipts")) action = FailureAudit.Action.RECEIPT_CONFIRM;
    else if (path.matches("/api/funds/receipts/[^/]+/void")) {
      action = FailureAudit.Action.RECEIPT_VOID;
      try {
        resource =
            UUID.fromString(
                path.substring("/api/funds/receipts/".length(), path.length() - "/void".length()));
      } catch (IllegalArgumentException invalid) {
        /* 不保存原始路径文本。 */
      }
    } else if (path.equals("/api/funds/allocations"))
      action = FailureAudit.Action.ALLOCATION_CREATE;
    else if (path.matches("/api/funds/allocations/[^/]+/release")) {
      action = FailureAudit.Action.ALLOCATION_RELEASE;
      try {
        resource =
            UUID.fromString(
                path.substring(
                    "/api/funds/allocations/".length(), path.length() - "/release".length()));
      } catch (IllegalArgumentException invalid) {
        /* 不保存原始路径文本。 */
      }
    } else if (path.equals("/api/product/curricula"))
      action = FailureAudit.Action.CURRICULUM_CREATE;
    else if (path.matches("/api/product/curricula/[^/]+/update")) {
      action = FailureAudit.Action.CURRICULUM_UPDATE;
      try {
        resource =
            UUID.fromString(
                path.substring(
                    "/api/product/curricula/".length(), path.length() - "/update".length()));
      } catch (IllegalArgumentException invalid) {
        /* 不保存原始路径文本。 */
      }
    } else if (path.equals("/api/product/groups"))
      action = FailureAudit.Action.PRODUCT_GROUP_CREATE;
    else if (path.matches("/api/product/groups/[^/]+/update")) {
      action = FailureAudit.Action.PRODUCT_GROUP_UPDATE;
      try {
        resource =
            UUID.fromString(
                path.substring(
                    "/api/product/groups/".length(), path.length() - "/update".length()));
      } catch (IllegalArgumentException invalid) {
        /* 不保存原始路径文本。 */
      }
    } else if (path.matches("/api/product/products/[^/]+/group(/clear)?")) {
      action = FailureAudit.Action.PRODUCT_GROUP_ASSIGN;
      try {
        resource =
            UUID.fromString(
                path.substring(
                    "/api/product/products/".length(),
                    path.indexOf("/group", "/api/product/products/".length())));
      } catch (IllegalArgumentException invalid) {
        /* 不保存原始路径文本。 */
      }
    } else if (path.equals("/api/product/products")) action = FailureAudit.Action.PRODUCT_CREATE;
    else if (path.matches("/api/product/products/[^/]+/update")) {
      action = FailureAudit.Action.PRODUCT_UPDATE;
      try {
        resource =
            UUID.fromString(
                path.substring(
                    "/api/product/products/".length(), path.length() - "/update".length()));
      } catch (IllegalArgumentException invalid) {
        /* 不保存原始路径文本。 */
      }
    } else if (path.equals("/api/product/price-versions"))
      action = FailureAudit.Action.PRICE_VERSION_CREATE;
    else if (path.matches("/api/product/price-versions/[^/]+/(update|publish|retire)")) {
      action =
          path.endsWith("/update")
              ? FailureAudit.Action.PRICE_VERSION_UPDATE
              : path.endsWith("/publish")
                  ? FailureAudit.Action.PRICE_VERSION_PUBLISH
                  : FailureAudit.Action.PRICE_VERSION_RETIRE;
      String suffix = path.substring(path.lastIndexOf('/'));
      try {
        resource =
            UUID.fromString(
                path.substring(
                    "/api/product/price-versions/".length(), path.length() - suffix.length()));
      } catch (IllegalArgumentException invalid) {
        /* 不保存原始路径文本。 */
      }
    } else if (path.equals("/api/archive/students")) action = FailureAudit.Action.STUDENT_CREATE;
    else if (path.matches("/api/archive/students/[^/]+/(update|status)")) {
      action = FailureAudit.Action.STUDENT_UPDATE;
      String suffix = path.endsWith("/update") ? "/update" : "/status";
      try {
        resource =
            UUID.fromString(
                path.substring("/api/archive/students/".length(), path.length() - suffix.length()));
      } catch (IllegalArgumentException invalid) {
        /* 不保存原始路径文本。 */
      }
    } else if (path.matches("/api/archive/students/[^/]+")) {
      // 读取敏感字段被拒绝时也要留下追踪；路径解析失败则不记录。
      action = FailureAudit.Action.STUDENT_SENSITIVE_READ;
      try {
        resource = UUID.fromString(path.substring("/api/archive/students/".length()));
      } catch (IllegalArgumentException invalid) {
        /* 不保存原始路径文本。 */
      }
    } else if (path.equals("/api/employee/me/email-config"))
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
