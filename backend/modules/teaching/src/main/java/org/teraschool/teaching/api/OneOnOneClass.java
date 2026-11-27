package org.teraschool.teaching.api;

import java.util.UUID;

/**
 * 按一条已生效的一对一报名自动开班并进班。
 *
 * <p>班级容量固定 1、班型 ONE_ON_ONE、校区继承报名；开班与结班日期缺省取今天与报名有效期。 重复提交按 requestId 幂等；同一报名已有在读的一对一班级时直接返回该班级。
 */
public record OneOnOneClass(UUID requestId, String beginsOn, String endsOn) {}
