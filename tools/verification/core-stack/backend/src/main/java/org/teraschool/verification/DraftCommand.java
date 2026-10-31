package org.teraschool.verification;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** 验证专用草稿；金额单位为元，不定义真实报名或退款规则。 */
public record DraftCommand(
    @NotBlank @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?")
    @Schema(type="string", description="正十进制金额，单位元", requiredMode=Schema.RequiredMode.REQUIRED) String amount,
    @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) LocalDate businessDate,
    @Size(max=200) @Schema(nullable=true) String note) {}
