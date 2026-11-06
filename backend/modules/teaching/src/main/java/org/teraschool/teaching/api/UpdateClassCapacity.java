package org.teraschool.teaching.api;

import java.util.UUID;

/** 修改进行中班级名额：版本、请求身份与原因用于并发保护和追溯。 */
public record UpdateClassCapacity(
    UUID requestId, Long expectedVersion, Integer capacity, String reason) {}
