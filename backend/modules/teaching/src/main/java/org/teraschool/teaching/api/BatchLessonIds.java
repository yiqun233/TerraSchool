package org.teraschool.teaching.api;

import java.util.List;
import java.util.UUID;

/** 批量发布/关闭课次的入参；课次按传入顺序逐个处理，已有状态的课次跳过并记录原因。 */
public record BatchLessonIds(UUID requestId, List<UUID> lessonIds) {}
