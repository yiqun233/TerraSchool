package org.teraschool.product.api;

import java.util.UUID;

/** 产品组合里的一门课程及其课时分配。 */
public record ProductCurriculum(UUID curriculumId, String curriculumName, String hours) {}
