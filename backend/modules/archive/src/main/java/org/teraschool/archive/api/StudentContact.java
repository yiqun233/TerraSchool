package org.teraschool.archive.api;

import java.util.UUID;

/** 学员关联的联系人；{@code relation} 是稳定业务代码，不存显示名称。 */
public record StudentContact(
    UUID id,
    String name,
    String relation,
    boolean primary,
    String phone,
    String phoneAlt,
    String email,
    String wechat) {}
