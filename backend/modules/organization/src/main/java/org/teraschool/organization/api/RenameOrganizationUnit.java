package org.teraschool.organization.api;

/** 版本来自最近读取结果，避免覆盖其他员工已经提交的名称。 */
public record RenameOrganizationUnit(Long expectedVersion, String name) {}
