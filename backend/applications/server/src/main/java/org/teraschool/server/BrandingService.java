package org.teraschool.server;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.platform.api.ObjectStorage;
import org.teraschool.platform.api.TenantTransactions;
import tools.jackson.databind.json.JsonMapper;

/**
 * 租户品牌读写。
 *
 * <p>颜色派生在服务端完成，租户端、平台端、导出与打印读同一份结果；页面只消费令牌。 租户写入使用 {@code app.tenant_id} 事务局部变量与 RLS；平台写入使用
 * ts_platform， 两张表都禁止绕过租户策略。历史保留最近 10 版，回滚总是生成新版本。
 */
@Service
class BrandingService {
  private static final String DEFAULT_SEED = "#254c78";
  private static final int MAX_HISTORY = 10;

  /** Logo 体积上限：与前端提示一致，先于解码生效。 */
  static final int MAX_LOGO_BYTES = 204800;

  /** 尺寸上限：先读图片头再解码，避免用合法体积提交超大像素。 */
  private static final int MAX_LOGO_EDGE = 2048;

  private static final long MAX_LOGO_PIXELS = 4_194_304L;

  private final JdbcTemplate app;
  private final JdbcTemplate platform;
  private final TenantTransactions tenantTransactions;
  private final TransactionTemplate platformTransactions;
  private final CurrentActor actors;
  private final ObjectStorage storage;
  private final JsonMapper json = JsonMapper.builder().build();

  BrandingService(
      @Qualifier("appDataSource") DataSource appSource,
      @Qualifier("platformDataSource") DataSource platformSource,
      TenantTransactions tenantTransactions,
      CurrentActor actors,
      ObjectStorage storage) {
    this.app = new JdbcTemplate(appSource);
    this.platform = new JdbcTemplate(platformSource);
    this.tenantTransactions = tenantTransactions;
    this.platformTransactions =
        new TransactionTemplate(new DataSourceTransactionManager(platformSource));
    this.actors = actors;
    this.storage = storage;
  }

  record PresetView(String name, String seed) {}

  record HistoryView(
      int version,
      String displayName,
      String seed,
      String brand,
      String changedAt,
      String changedByKind) {}

  record BrandingView(
      String displayName,
      String seed,
      String brand,
      String brandHover,
      String brandActive,
      String brandSoft,
      String brandLine,
      String brandLink,
      String onBrand,
      String logoWideUrl,
      String logoMarkUrl,
      boolean adjusted,
      int algoVersion,
      int version,
      boolean canWrite,
      List<PresetView> presets) {}

  private record Row(
      String displayName,
      String seed,
      String brand,
      String brandHover,
      String brandActive,
      String brandSoft,
      String brandLine,
      String brandLink,
      String onBrand,
      String logoWideUrl,
      String logoMarkUrl,
      boolean adjusted,
      int algoVersion,
      int version) {}

  private record Snapshot(
      String displayName,
      String seed,
      String brand,
      String brandHover,
      String brandActive,
      String brandSoft,
      String brandLine,
      String brandLink,
      String onBrand,
      String logoWideUrl,
      String logoMarkUrl,
      boolean adjusted,
      int algoVersion) {}

  record Update(String displayName, String seed, Integer expectedVersion) {}

  record LogoContent(byte[] bytes, String contentType) {}

  private record LogoRef(String objectKey, String contentType) {}

  static class LogoUnavailable extends RuntimeException {
    LogoUnavailable() {
      super("对象存储尚未配置");
    }
  }

  static class LogoInvalid extends RuntimeException {
    LogoInvalid(String message) {
      super(message);
    }
  }

  static class LogoNotFound extends RuntimeException {
    LogoNotFound() {
      super("Logo 不存在");
    }
  }

  static class VersionConflict extends RuntimeException {}

  static class InvalidBranding extends RuntimeException {
    InvalidBranding(String message) {
      super(message);
    }
  }

  BrandingView tenant(UUID tenantId, boolean canWrite) {
    var row =
        tenantTransactions.execute(
            tenantId,
            () ->
                app
                    .query(
                        "select display_name,primary_seed,brand,brand_hover,brand_active,"
                            + "brand_soft,brand_line,brand_link,on_brand,logo_wide_url,"
                            + "logo_mark_url,adjusted,algo_version,version"
                            + " from identity.tenant_branding where tenant_id=?",
                        (rs, index) ->
                            new Row(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getString(5),
                                rs.getString(6),
                                rs.getString(7),
                                rs.getString(8),
                                rs.getString(9),
                                rs.getString(10),
                                rs.getString(11),
                                rs.getBoolean(12),
                                rs.getInt(13),
                                rs.getInt(14)),
                        tenantId)
                    .stream()
                    .findFirst());
    return view(row.orElse(defaults()), canWrite);
  }

  BrandingView platformTenant(UUID tenantId, boolean canWrite) {
    var row =
        platform
            .query(
                "select display_name,primary_seed,brand,brand_hover,brand_active,"
                    + "brand_soft,brand_line,brand_link,on_brand,logo_wide_url,"
                    + "logo_mark_url,adjusted,algo_version,version"
                    + " from identity.tenant_branding where tenant_id=?",
                (rs, index) ->
                    new Row(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getString(7),
                        rs.getString(8),
                        rs.getString(9),
                        rs.getString(10),
                        rs.getString(11),
                        rs.getBoolean(12),
                        rs.getInt(13),
                        rs.getInt(14)),
                tenantId)
            .stream()
            .findFirst();
    return view(row.orElse(defaults(tenantName(tenantId))), canWrite);
  }

  BrandingView updateTenant(UUID tenantId, UUID accountId, Update update) {
    // 应用服务自己复核租户级动作，不依赖 HTTP 适配层先检查过。
    var permissions = actors.permissions();
    if (!permissions.canWriteBranding())
      throw new org.springframework.security.access.AccessDeniedException("无品牌写入权限");
    if (!permissions.tenantId().equals(tenantId) || !permissions.accountId().equals(accountId))
      throw new org.springframework.security.access.AccessDeniedException("不能修改其他租户品牌");
    var palette = palette(update.seed());
    return tenantTransactions.execute(
        tenantId, () -> view(write(app, tenantId, accountId, "TENANT", update, palette), true));
  }

  /**
   * 上传横版或方形 Logo。
   *
   * <p>首版只接受 PNG/JPEG，拒绝 SVG/GIF，避免未消毒脚本与动画；服务端读取图片尺寸并校验比例， 内容按版本写入私有对象存储，数据库只存对象键。单色版暂未开放。
   */
  BrandingView uploadLogo(
      UUID tenantId, UUID accountId, String kind, byte[] bytes, String ignoredContentType) {
    var permissions = requireTenantBrandingWriter(tenantId, accountId);
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_LOGO_BYTES)
      throw new LogoInvalid("Logo 需为不超过 200KB 的图片");
    var cleanKind = kind == null ? "" : kind.strip().toUpperCase(java.util.Locale.ROOT);
    if (!Set.of("WIDE", "MARK").contains(cleanKind)) throw new LogoInvalid("首版只支持横版与方形 Logo");
    var format = sniffFormat(bytes);
    validateDimensions(cleanKind, bytes, format);
    if (!storage.configured()) throw new LogoUnavailable();

    var objectKey =
        "branding/"
            + tenantId
            + "/"
            + cleanKind.toLowerCase(java.util.Locale.ROOT)
            + "/"
            + UUID.randomUUID();
    storage.put(objectKey, bytes, format);
    try {
      var row =
          tenantTransactions.execute(
              tenantId,
              () -> {
                var existing = lock(app, tenantId);
                var current = existing == null ? defaults() : existing;
                var assetVersion =
                    app.queryForObject(
                        "select coalesce(max(version),0)+1 from identity.tenant_branding_logo"
                            + " where tenant_id=? and kind=?",
                        Integer.class,
                        tenantId,
                        cleanKind);
                app.update(
                    "insert into identity.tenant_branding_logo"
                        + "(tenant_id,kind,version,object_key,content_type,byte_size)"
                        + " values (?,?,?,?,?,?)",
                    tenantId,
                    cleanKind,
                    assetVersion,
                    objectKey,
                    format,
                    bytes.length);
                var url = "/api/tenant/branding/logo/" + cleanKind + "/" + assetVersion;
                var next =
                    new Row(
                        current.displayName(),
                        current.seed(),
                        current.brand(),
                        current.brandHover(),
                        current.brandActive(),
                        current.brandSoft(),
                        current.brandLine(),
                        current.brandLink(),
                        current.onBrand(),
                        "WIDE".equals(cleanKind) ? url : current.logoWideUrl(),
                        "MARK".equals(cleanKind) ? url : current.logoMarkUrl(),
                        current.adjusted(),
                        current.algoVersion(),
                        current.version() + 1);
                upsert(app, tenantId, accountId, "TENANT", next);
                history(app, tenantId, accountId, "TENANT", next);
                prune(app, tenantId);
                return next;
              });
      return view(row, permissions.canWriteBranding());
    } catch (RuntimeException failure) {
      // 数据库失败时尽量清理刚写入的对象；对象存储失败不覆盖原始异常。
      try {
        storage.delete(objectKey);
      } catch (RuntimeException ignored) {
        // 清理失败留给存储生命周期任务处理。
      }
      throw failure;
    }
  }

  /** 读取指定版本的 Logo；版本化对象键保证历史记录不会读到新文件。 */
  LogoContent logo(UUID tenantId, String kind, int version) {
    var permissions = actors.permissions();
    if (!permissions.tenantId().equals(tenantId))
      throw new org.springframework.security.access.AccessDeniedException("不能读取其他租户 Logo");
    var cleanKind = kind == null ? "" : kind.strip().toUpperCase(java.util.Locale.ROOT);
    var ref =
        tenantTransactions.execute(
            tenantId,
            () ->
                app
                    .query(
                        "select object_key,content_type from identity.tenant_branding_logo"
                            + " where tenant_id=? and kind=? and version=?",
                        (rs, index) -> new LogoRef(rs.getString(1), rs.getString(2)),
                        tenantId,
                        cleanKind,
                        version)
                    .stream()
                    .findFirst()
                    .orElseThrow(LogoNotFound::new));
    if (!storage.configured()) throw new LogoUnavailable();
    return new LogoContent(storage.get(ref.objectKey()), ref.contentType());
  }

  private org.teraschool.identity.api.CurrentPermissions requireTenantBrandingWriter(
      UUID tenantId, UUID accountId) {
    var permissions = actors.permissions();
    if (!permissions.canWriteBranding()
        || !permissions.tenantId().equals(tenantId)
        || !permissions.accountId().equals(accountId))
      throw new org.springframework.security.access.AccessDeniedException("无品牌写入权限");
    return permissions;
  }

  private String sniffFormat(byte[] bytes) {
    if (bytes.length >= 8
        && (bytes[0] & 0xff) == 0x89
        && bytes[1] == 'P'
        && bytes[2] == 'N'
        && bytes[3] == 'G') return "image/png";
    if (bytes.length >= 3
        && (bytes[0] & 0xff) == 0xff
        && (bytes[1] & 0xff) == 0xd8
        && (bytes[2] & 0xff) == 0xff) return "image/jpeg";
    throw new LogoInvalid("Logo 只支持 PNG 或 JPEG");
  }

  /**
   * 先读图片头拿尺寸并做上限校验，再解码确认内容。
   *
   * <p>解码会按声明尺寸分配像素缓冲，体积小但声明像素极大的图片足以耗尽堆；因此上限必须在解码之前生效，解码只在已通过上限的输入上进行。
   */
  private void validateDimensions(String kind, byte[] bytes, String format) {
    var size = "image/png".equals(format) ? pngSize(bytes) : jpegSize(bytes);
    int width = size[0];
    int height = size[1];
    int shortEdge = Math.min(width, height);
    int longEdge = Math.max(width, height);
    if (shortEdge < 32) throw new LogoInvalid("Logo 最小边不得小于 32px");
    if (longEdge > MAX_LOGO_EDGE) throw new LogoInvalid("Logo 最大边长不得超过 " + MAX_LOGO_EDGE + "px");
    if ((long) width * height > MAX_LOGO_PIXELS)
      throw new LogoInvalid("Logo 总像素不得超过 " + MAX_LOGO_PIXELS / 10_000 + " 万");
    var ratio = (double) width / height;
    if ("WIDE".equals(kind) && (ratio < 1.0 || ratio > 4.0))
      throw new LogoInvalid("横版 Logo 宽高比需在 1:1 至 4:1");
    if ("MARK".equals(kind) && (ratio < 0.9 || ratio > 1.1))
      throw new LogoInvalid("方形 Logo 宽高比需接近 1:1");
    try (var input = new ByteArrayInputStream(bytes)) {
      if (ImageIO.read(input) == null) throw new LogoInvalid("Logo 图片无法读取");
    } catch (IOException invalid) {
      throw new LogoInvalid("Logo 图片无法读取");
    }
  }

  /** PNG：签名之后紧跟 IHDR，尺寸在固定偏移；不依赖解码器即可读出。 */
  private static int[] pngSize(byte[] bytes) {
    if (bytes.length < 24
        || bytes[12] != 'I'
        || bytes[13] != 'H'
        || bytes[14] != 'D'
        || bytes[15] != 'R') throw new LogoInvalid("Logo 图片无法读取");
    int width = readInt(bytes, 16);
    int height = readInt(bytes, 20);
    if (width <= 0 || height <= 0) throw new LogoInvalid("Logo 图片无法读取");
    return new int[] {width, height};
  }

  /** JPEG：沿段表找到 SOFn，高度在段内 +3、宽度在 +5。 */
  private static int[] jpegSize(byte[] bytes) {
    int index = 2;
    while (index + 8 < bytes.length) {
      if ((bytes[index] & 0xff) != 0xff) throw new LogoInvalid("Logo 图片无法读取");
      int marker = bytes[index + 1] & 0xff;
      if (marker == 0xff) {
        index += 1;
        continue;
      }
      // 无长度的独立标记：TEM、RSTn 与 SOI。
      if (marker == 0x01 || marker == 0xd8 || (marker >= 0xd0 && marker <= 0xd7)) {
        index += 2;
        continue;
      }
      if (marker == 0xd9 || marker == 0xda) break;
      int length = ((bytes[index + 2] & 0xff) << 8) | (bytes[index + 3] & 0xff);
      if (length < 2) throw new LogoInvalid("Logo 图片无法读取");
      boolean startOfFrame =
          marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc;
      if (startOfFrame) {
        int height = ((bytes[index + 5] & 0xff) << 8) | (bytes[index + 6] & 0xff);
        int width = ((bytes[index + 7] & 0xff) << 8) | (bytes[index + 8] & 0xff);
        if (width <= 0 || height <= 0) throw new LogoInvalid("Logo 图片无法读取");
        return new int[] {width, height};
      }
      index += 2 + length;
    }
    throw new LogoInvalid("Logo 图片无法读取");
  }

  private static int readInt(byte[] bytes, int offset) {
    return ((bytes[offset] & 0xff) << 24)
        | ((bytes[offset + 1] & 0xff) << 16)
        | ((bytes[offset + 2] & 0xff) << 8)
        | (bytes[offset + 3] & 0xff);
  }

  BrandingView updatePlatform(UUID tenantId, UUID operatorId, Update update) {
    var palette = palette(update.seed());
    return platformTransactions.execute(
        status -> {
          var row = write(platform, tenantId, operatorId, "PLATFORM", update, palette);
          platform.update(
              "insert into platform.audit_event(operator_id,action,tenant_id,changed_fields,trace_id)"
                  + " values (?,?,?,?,uuidv7())",
              operatorId,
              "TENANT_BRANDING_UPDATE",
              tenantId,
              "seed=" + palette.seed());
          return view(row, true);
        });
  }

  /** 回滚把历史快照复制成新版本；不删除历史，也不改写旧版本。 */
  BrandingView rollbackPlatform(UUID tenantId, UUID operatorId, int targetVersion) {
    return platformTransactions.execute(
        status -> {
          var snapshots =
              platform.query(
                  "select snapshot::text from identity.tenant_branding_history"
                      + " where tenant_id=? and version=?",
                  (rs, index) -> rs.getString(1),
                  tenantId,
                  targetVersion);
          if (snapshots.isEmpty()) throw new InvalidBranding("要回滚的版本不存在");
          Snapshot snapshot;
          try {
            snapshot = json.readValue(snapshots.getFirst(), Snapshot.class);
          } catch (Exception invalid) {
            throw new IllegalStateException("品牌历史快照无法解析", invalid);
          }
          var row = writeSnapshot(platform, tenantId, operatorId, "PLATFORM", snapshot);
          platform.update(
              "insert into platform.audit_event(operator_id,action,tenant_id,changed_fields,trace_id)"
                  + " values (?,?,?,?,uuidv7())",
              operatorId,
              "TENANT_BRANDING_ROLLBACK",
              tenantId,
              "version=" + targetVersion);
          return view(row, true);
        });
  }

  /** 平台查看最近版本；只读历史快照中的呈现字段，不返回操作者内部 id。 */
  List<HistoryView> historyPlatform(UUID tenantId) {
    return platform.query(
        "select version,snapshot::text,changed_at,changed_by_kind"
            + " from identity.tenant_branding_history"
            + " where tenant_id=? order by version desc limit "
            + MAX_HISTORY,
        (rs, index) -> {
          Snapshot snapshot;
          try {
            snapshot = json.readValue(rs.getString(2), Snapshot.class);
          } catch (Exception invalid) {
            throw new IllegalStateException("品牌历史快照无法解析", invalid);
          }
          return new HistoryView(
              rs.getInt(1),
              snapshot.displayName(),
              snapshot.seed(),
              snapshot.brand(),
              rs.getObject(3, java.time.OffsetDateTime.class).toString(),
              rs.getString(4));
        },
        tenantId);
  }

  private Row write(
      JdbcTemplate jdbc,
      UUID tenantId,
      UUID actorId,
      String actorKind,
      Update update,
      BrandPalette.Palette palette) {
    var existing = lock(jdbc, tenantId);
    var currentVersion = existing == null ? 0 : existing.version();
    var expected = update.expectedVersion() == null ? currentVersion : update.expectedVersion();
    if (expected != currentVersion) throw new VersionConflict();
    var displayName = normalizeDisplayName(update.displayName());
    var row =
        new Row(
            displayName,
            palette.seed(),
            palette.brand(),
            palette.brandHover(),
            palette.brandActive(),
            palette.brandSoft(),
            palette.brandLine(),
            palette.brandLink(),
            palette.onBrand(),
            existing == null ? null : existing.logoWideUrl(),
            existing == null ? null : existing.logoMarkUrl(),
            palette.adjusted(),
            palette.algoVersion(),
            currentVersion + 1);
    upsert(jdbc, tenantId, actorId, actorKind, row);
    history(jdbc, tenantId, actorId, actorKind, row);
    prune(jdbc, tenantId);
    return row;
  }

  private Row writeSnapshot(
      JdbcTemplate jdbc, UUID tenantId, UUID actorId, String actorKind, Snapshot snapshot) {
    var existing = lock(jdbc, tenantId);
    var currentVersion = existing == null ? 0 : existing.version();
    var row =
        new Row(
            snapshot.displayName(),
            snapshot.seed(),
            snapshot.brand(),
            snapshot.brandHover(),
            snapshot.brandActive(),
            snapshot.brandSoft(),
            snapshot.brandLine(),
            snapshot.brandLink(),
            snapshot.onBrand(),
            snapshot.logoWideUrl(),
            snapshot.logoMarkUrl(),
            snapshot.adjusted(),
            snapshot.algoVersion(),
            currentVersion + 1);
    upsert(jdbc, tenantId, actorId, actorKind, row);
    history(jdbc, tenantId, actorId, actorKind, row);
    prune(jdbc, tenantId);
    return row;
  }

  private Row lock(JdbcTemplate jdbc, UUID tenantId) {
    // 首次写入时品牌行还不存在，FOR UPDATE 锁不到任何行：并发请求会各自算出同一个版本号，
    // 再在主键上相撞并抛出数据库异常。先用租户级事务咨询锁把「无行」的窗口也串行化。
    org.springframework.jdbc.core.ResultSetExtractor<Void> acquire = result -> null;
    jdbc.query(
        "select pg_advisory_xact_lock(hashtextextended(cast(? as text), 0))",
        acquire,
        tenantId.toString());
    return jdbc
        .query(
            "select display_name,primary_seed,brand,brand_hover,brand_active,"
                + "brand_soft,brand_line,brand_link,on_brand,logo_wide_url,"
                + "logo_mark_url,adjusted,algo_version,version"
                + " from identity.tenant_branding where tenant_id=? for update",
            (rs, index) ->
                new Row(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getString(7),
                    rs.getString(8),
                    rs.getString(9),
                    rs.getString(10),
                    rs.getString(11),
                    rs.getBoolean(12),
                    rs.getInt(13),
                    rs.getInt(14)),
            tenantId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private void upsert(JdbcTemplate jdbc, UUID tenantId, UUID actorId, String actorKind, Row row) {
    jdbc.update(
        "insert into identity.tenant_branding(tenant_id,primary_seed,brand,brand_hover,"
            + "brand_active,brand_soft,brand_line,brand_link,on_brand,logo_wide_url,"
            + "logo_mark_url,display_name,adjusted,algo_version,version,updated_by,updated_by_kind)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            + " on conflict (tenant_id) do update set primary_seed=excluded.primary_seed,"
            + " brand=excluded.brand,brand_hover=excluded.brand_hover,"
            + " brand_active=excluded.brand_active,brand_soft=excluded.brand_soft,"
            + " brand_line=excluded.brand_line,brand_link=excluded.brand_link,"
            + " on_brand=excluded.on_brand,logo_wide_url=excluded.logo_wide_url,"
            + " logo_mark_url=excluded.logo_mark_url,display_name=excluded.display_name,"
            + " adjusted=excluded.adjusted,algo_version=excluded.algo_version,"
            + " version=excluded.version,updated_by=excluded.updated_by,"
            + " updated_by_kind=excluded.updated_by_kind,updated_at=now()",
        tenantId,
        row.seed(),
        row.brand(),
        row.brandHover(),
        row.brandActive(),
        row.brandSoft(),
        row.brandLine(),
        row.brandLink(),
        row.onBrand(),
        row.logoWideUrl(),
        row.logoMarkUrl(),
        row.displayName(),
        row.adjusted(),
        row.algoVersion(),
        row.version(),
        actorId,
        actorKind);
  }

  private void history(JdbcTemplate jdbc, UUID tenantId, UUID actorId, String actorKind, Row row) {
    try {
      jdbc.update(
          "insert into identity.tenant_branding_history"
              + "(tenant_id,version,snapshot,changed_by,changed_by_kind)"
              + " values (?,?,?::jsonb,?,?)",
          tenantId,
          row.version(),
          json.writeValueAsString(
              new Snapshot(
                  row.displayName(),
                  row.seed(),
                  row.brand(),
                  row.brandHover(),
                  row.brandActive(),
                  row.brandSoft(),
                  row.brandLine(),
                  row.brandLink(),
                  row.onBrand(),
                  row.logoWideUrl(),
                  row.logoMarkUrl(),
                  row.adjusted(),
                  row.algoVersion())),
          actorId,
          actorKind);
    } catch (Exception invalid) {
      throw new IllegalStateException("品牌历史写入失败", invalid);
    }
  }

  private void prune(JdbcTemplate jdbc, UUID tenantId) {
    jdbc.update(
        "delete from identity.tenant_branding_history where tenant_id=? and version < ("
            + " select version from identity.tenant_branding_history"
            + " where tenant_id=? order by version desc offset ? limit 1)",
        tenantId,
        tenantId,
        MAX_HISTORY - 1);
  }

  private String tenantName(UUID tenantId) {
    var names =
        platform.query(
            "select name from identity.tenant where id=?",
            (rs, index) -> rs.getString(1),
            tenantId);
    return names.isEmpty() ? null : names.getFirst();
  }

  private BrandPalette.Palette palette(String seed) {
    var clean = seed == null || seed.isBlank() ? DEFAULT_SEED : seed.trim();
    if (!BrandPalette.isPreset(clean)) throw new InvalidBranding("首版只允许平台预设品牌色");
    return BrandPalette.derive(clean);
  }

  private String normalizeDisplayName(String value) {
    if (value == null || value.isBlank()) return null;
    var clean = value.trim();
    if (clean.length() > 60) throw new InvalidBranding("系统显示名最多 60 个字符");
    return clean;
  }

  private Row defaults() {
    return defaults(null);
  }

  private Row defaults(String displayName) {
    var palette = BrandPalette.derive(DEFAULT_SEED);
    return new Row(
        displayName,
        palette.seed(),
        palette.brand(),
        palette.brandHover(),
        palette.brandActive(),
        palette.brandSoft(),
        palette.brandLine(),
        palette.brandLink(),
        palette.onBrand(),
        null,
        null,
        palette.adjusted(),
        palette.algoVersion(),
        0);
  }

  private BrandingView view(Row row, boolean canWrite) {
    return new BrandingView(
        row.displayName(),
        row.seed(),
        row.brand(),
        row.brandHover(),
        row.brandActive(),
        row.brandSoft(),
        row.brandLine(),
        row.brandLink(),
        row.onBrand(),
        row.logoWideUrl(),
        row.logoMarkUrl(),
        row.adjusted(),
        row.algoVersion(),
        row.version(),
        canWrite,
        BrandPalette.PRESETS.stream()
            .map(preset -> new PresetView(preset.name(), preset.seed()))
            .toList());
  }

  /** 让 JSON 字段名保持稳定；快照解析不依赖数据库列顺序。 */
  static Map<String, Object> debugSnapshot(Snapshot snapshot) {
    return Map.of("seed", snapshot.seed());
  }
}
