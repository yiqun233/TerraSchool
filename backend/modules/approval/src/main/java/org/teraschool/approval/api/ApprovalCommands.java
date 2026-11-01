package org.teraschool.approval.api;

import java.util.UUID;

public interface ApprovalCommands {
  ApprovalPolicyView upsertPolicy(UpsertPolicy command);

  ApprovalRequestView request(CreateApprovalRequest command);

  ApprovalRequestView decide(UUID id, DecideApproval command);
}
