package org.teraschool.server;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Persistent scheduling uses only the dedicated scheduler role; business work re-enters the app
 * role.
 */
@Configuration
class DurableSchedulerConfiguration {
  @Bean(initMethod = "start", destroyMethod = "stop")
  Scheduler durableScheduler(
      @Qualifier("schedulerDataSource") DataSource source,
      CustomerAutoRecycleJob autoRecycle,
      StudentExportTaskService exports,
      Environment environment) {
    var delay =
        Duration.ofMillis(
            environment.getProperty(
                "teraschool.admissions.auto-recycle.interval-ms", Long.class, 60_000L));
    if (delay.compareTo(Duration.ofSeconds(1)) < 0 || delay.compareTo(Duration.ofHours(1)) > 0)
      throw new IllegalArgumentException("自动回收任务间隔须在 1 秒到 1 小时之间");
    RecurringTask<Void> recycleTask =
        Tasks.recurring("customer-auto-recycle-v1", FixedDelay.of(delay))
            .execute((instance, context) -> autoRecycle.scheduledSweep());
    RecurringTask<Void> exportTask =
        Tasks.recurring("student-export-dispatch-v1", FixedDelay.of(Duration.ofSeconds(15)))
            .execute((instance, context) -> exports.dispatchPending());
    var builder =
        (autoRecycle.isEnabled() ? Scheduler.create(source) : Scheduler.create(source, recycleTask))
            .tableName("infra.scheduled_tasks")
            .threads(2)
            .pollingInterval(Duration.ofSeconds(2))
            .heartbeatInterval(Duration.ofSeconds(15))
            .missedHeartbeatsLimit(4)
            .enableImmediateExecution();
    if (autoRecycle.isEnabled()) builder.startTasks(recycleTask);
    builder.startTasks(exportTask);
    return builder.build();
  }
}
