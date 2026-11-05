package org.teraschool.teaching.api;

import java.util.UUID;

/** 校历中的整日停课记录；取消采用版本化历史，不删除已发布课次。 */
public record CreateSchoolClosure(UUID schoolId, String closedOn, String reason) {}
