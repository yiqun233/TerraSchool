package org.teraschool.teaching.api;

import java.util.UUID;

/** 修改课次备注；只动展示字段，不参与冲突与计费。 */
public record UpdateLessonNote(UUID requestId, Long expectedVersion, String memo) {}
