package org.teraschool.organization.api;

import java.util.UUID;

/** 校区之间的单程通勤分钟数；双向对称，0 表示未配置。 */
public record CampusDistance(UUID campusId, String campusName, int minutes) {}
