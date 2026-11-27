package org.teraschool.teaching.api;

import java.util.UUID;

/** 教师模式批量改派：把某教师日期段内的课次整体改派给另一位教师。 */
public record ReassignTeacher(UUID requestId, String from, String to, UUID newTeacherId) {}
