package org.teraschool.teaching.api;

import java.util.List;
import java.util.UUID;

/** 批量建课次结果：成功创建的课次、被跳过的日期与原因。 */
public record BatchLessonResult(int created, List<UUID> lessonIds, List<String> skipped) {}
