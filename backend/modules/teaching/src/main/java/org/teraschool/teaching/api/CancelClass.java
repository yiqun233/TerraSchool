package org.teraschool.teaching.api;

import java.util.UUID;

/** 取消空班：需要幂等请求身份、原因和打开班级时读取的版本。 */
public record CancelClass(UUID requestId, Long expectedVersion, String reason) {}
