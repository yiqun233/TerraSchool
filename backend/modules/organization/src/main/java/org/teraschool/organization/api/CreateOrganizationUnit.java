package org.teraschool.organization.api;

import java.util.UUID;

/** 请求身份与业务主键分开；相同身份不得换参数再次执行。 */
public record CreateOrganizationUnit(UUID requestId, UUID schoolId, String kind, String name) {}
