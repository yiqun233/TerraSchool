package org.teraschool.teaching.api;

import java.util.UUID;

/** 把一条已生效报名放入班级；同一报名只保留一个当前主班。 */
public record AddClassMember(UUID enrollmentId) {}
