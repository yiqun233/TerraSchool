package org.teraschool.teaching.api;

import java.util.UUID;

/** 学校下的校区（报名、班级与课表的归属单位）。 */
public record CampusBrief(UUID id, String name) {}
