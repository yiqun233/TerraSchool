package org.teraschool.verification;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 公开结果与 jOOQ 生成类型分离，金额显式为字符串，实际时刻显式为 UTC。 */
public record DraftView(
    @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(type="string", requiredMode=Schema.RequiredMode.REQUIRED) String amount,
    @Schema(requiredMode=Schema.RequiredMode.REQUIRED) LocalDate businessDate,
    @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant happenedAt,
    @Schema(nullable=true) String note) {}
