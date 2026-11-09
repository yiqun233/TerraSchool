package org.teraschool.server;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.teraschool.identity.api.CurrentActor;

/** 租户品牌：登录态读取，TENANT_BRANDING_WRITE 才能写入。 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
class BrandingHttp {
  private final BrandingService branding;
  private final CurrentActor actors;

  BrandingHttp(BrandingService branding, CurrentActor actors) {
    this.branding = branding;
    this.actors = actors;
  }

  record Update(String displayName, String seed, Integer expectedVersion) {}

  @GetMapping("/api/tenant/branding")
  BrandingService.BrandingView get() {
    var permissions = actors.permissions();
    return branding.tenant(permissions.tenantId(), permissions.canWriteBranding());
  }

  @PutMapping(value = "/api/tenant/branding", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> put(
      @RequestBody Update body,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      HttpServletResponse response) {
    var permissions = actors.permissions();
    if (!permissions.canWriteBranding()) return ResponseEntity.status(403).build();
    try {
      // 解析必须在品牌错误映射之内：非法 If-Match 是客户端输入错误，不能落成 500。
      var expected = expectedVersion(body, ifMatch);
      return ResponseEntity.ok(
          branding.updateTenant(
              permissions.tenantId(),
              permissions.accountId(),
              new BrandingService.Update(body.displayName(), body.seed(), expected)));
    } catch (BrandingService.VersionConflict conflict) {
      return problem(response, 409, "BRANDING_VERSION_CONFLICT", "品牌已被其他人修改，请刷新后重试");
    } catch (BrandingService.InvalidBranding invalid) {
      return problem(response, 400, "INVALID_BRANDING", invalid.getMessage());
    }
  }

  @PostMapping(value = "/api/tenant/branding/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ResponseEntity<?> uploadLogo(
      @RequestParam String kind,
      @RequestPart("file") MultipartFile file,
      HttpServletResponse response) {
    var permissions = actors.permissions();
    if (!permissions.canWriteBranding()) return ResponseEntity.status(403).build();
    // 先按声明体积拒绝，避免把容器允许的 5MB 复制进堆再判定。
    if (file.getSize() > BrandingService.MAX_LOGO_BYTES)
      return problem(response, 400, "INVALID_LOGO", "Logo 需为不超过 200KB 的图片");
    try {
      return ResponseEntity.ok(
          branding.uploadLogo(
              permissions.tenantId(),
              permissions.accountId(),
              kind,
              file.getBytes(),
              file.getContentType()));
    } catch (BrandingService.LogoInvalid invalid) {
      return problem(response, 400, "INVALID_LOGO", invalid.getMessage());
    } catch (BrandingService.LogoUnavailable unavailable) {
      return problem(response, 409, "STORAGE_NOT_CONFIGURED", unavailable.getMessage());
    } catch (java.io.IOException invalid) {
      return problem(response, 400, "INVALID_LOGO", "Logo 读取失败");
    }
  }

  @GetMapping("/api/tenant/branding/logo/{kind}/{version}")
  ResponseEntity<?> logo(@PathVariable String kind, @PathVariable int version) {
    var permissions = actors.permissions();
    try {
      var content = branding.logo(permissions.tenantId(), kind, version);
      return ResponseEntity.ok()
          .contentType(MediaType.parseMediaType(content.contentType()))
          .cacheControl(CacheControl.noStore())
          .body(content.bytes());
    } catch (BrandingService.LogoNotFound missing) {
      return ResponseEntity.status(404).build();
    } catch (BrandingService.LogoUnavailable unavailable) {
      return ResponseEntity.status(409).build();
    }
  }

  private static Integer expectedVersion(Update body, String ifMatch) {
    if (body.expectedVersion() != null) return body.expectedVersion();
    if (ifMatch == null || ifMatch.isBlank()) return null;
    try {
      return Integer.valueOf(ifMatch.replace("\"", "").trim());
    } catch (NumberFormatException invalid) {
      throw new BrandingService.InvalidBranding("If-Match 必须是版本号");
    }
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpServletResponse response, int status, String code, String detail) {
    var problem = ProblemDetail.forStatus(status);
    problem.setTitle(code);
    problem.setDetail(detail);
    problem.setProperty("code", code);
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }
}
