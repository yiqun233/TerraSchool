package org.teraschool.verification;

import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/** 只做协议适配；租户、权限和业务验证仍在应用服务执行。 */
@RestController
@RequestMapping("/probe")
class ProbeController {
    private final DraftService service;
    ProbeController(DraftService service) { this.service = service; }

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) { return Map.of("token", token.getToken(), "headerName", token.getHeaderName(), "parameterName", token.getParameterName()); }

    @PostMapping("/drafts") @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId="createProbeDraft", summary="创建隔离验证草稿")
    DraftView create(@RequestBody DraftCommand request) { return service.create(request); }

    @GetMapping("/drafts")
    @Operation(operationId="listProbeDrafts", summary="查询当前测试租户草稿")
    List<DraftView> list() { return service.list(); }
}
