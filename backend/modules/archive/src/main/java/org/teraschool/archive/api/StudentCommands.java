package org.teraschool.archive.api;

import java.util.UUID;

/**
 * 学习档案的调用契约。
 *
 * <p>页面、受控任务和未来的外部入口都调用这里；授权、校验、版本与审计在应用服务内完成，HTTP 只是适配层。
 */
public interface StudentCommands {
  StudentCreation create(CreateStudent command);

  StudentProfile update(UUID id, UpdateStudent command);

  /** 只改状态，避免把脱敏值当成权威字段写回。 */
  StudentProfile changeStatus(UUID id, ChangeStudentStatus command);
}
