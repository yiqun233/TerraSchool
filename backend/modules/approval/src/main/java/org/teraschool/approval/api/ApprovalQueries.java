package org.teraschool.approval.api;

import java.util.List;
import java.util.UUID;

public interface ApprovalQueries {
  ApprovalCapabilities capabilities();

  List<ApprovalPolicyView> policies(UUID schoolId);

  List<ApprovalRequestView> requests(UUID schoolId, String status);
}
