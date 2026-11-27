package org.teraschool.teaching.api;

import java.util.UUID;

/** 教师下拉的最小视图：改派、筛选只需要编号与姓名。 */
public record TeacherBrief(UUID id, String name) {}
