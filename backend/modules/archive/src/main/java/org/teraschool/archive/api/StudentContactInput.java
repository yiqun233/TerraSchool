package org.teraschool.archive.api;

/**
 * 新建或修改时提交的联系人内容。
 *
 * <p>姓名与手机号都相同才复用已有联系人；仅手机号相同只提示核验，不合并两位家长。
 */
public record StudentContactInput(
    String name,
    String relation,
    String phone,
    String phoneAlt,
    String email,
    String wechat,
    boolean primary) {}
