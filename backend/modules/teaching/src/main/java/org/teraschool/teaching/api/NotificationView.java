package org.teraschool.teaching.api;

import java.util.UUID;

/** 站内信：排课事件通知，首版学校级、不区分接收人。 */
public record NotificationView(
    UUID id, String kind, String title, String body, String createdAt, boolean read) {}
