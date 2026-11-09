import { useEffect, useState } from "react";
import { Alert, App as AntApp, Button, Input } from "antd";
import { csrf } from "../../shared/http";
import { useSession } from "../../app/session";
import { useTenantBranding } from "../../shared/theme/TenantBrandingProvider";
import { normalizeBranding } from "../../shared/theme/brand";
import { Page, PageHeader, Panel, StateBlock } from "../../shared/ui/Page";
import styles from "./settings.module.css";

/**
 * 租户品牌设置：只改品牌色与系统显示名。
 *
 * 布局、密度、圆角、字体与状态色由平台锁定；这里不提供暗色模式、自定义布局
 * 或第二套骨架。保存后服务端重新派生令牌并立即生效，前端拿响应直接应用。
 */
export function BrandingSettings() {
  const { session, onUnauthorized } = useSession();
  const {
    branding,
    apply,
    refresh,
    error: brandingError,
  } = useTenantBranding();
  const { message } = AntApp.useApp();
  const [displayName, setDisplayName] = useState(branding.displayName ?? "");
  const [seed, setSeed] = useState(branding.seed);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [logoBusy, setLogoBusy] = useState<"WIDE" | "MARK" | null>(null);
  const [logoError, setLogoError] = useState("");

  useEffect(() => {
    setDisplayName(branding.displayName ?? "");
    setSeed(branding.seed);
  }, [branding.displayName, branding.seed, branding.version]);

  if (brandingError) {
    return (
      <Page>
        <PageHeader
          title="品牌与外观"
          description="无法读取当前品牌设置；页面不会用默认色冒充机构配置。"
        />
        <Panel>
          <StateBlock
            title="品牌设置加载失败"
            hint="请检查网络或稍后重试；如果持续失败，请联系平台运营。"
            action={<Button onClick={refresh}>重试</Button>}
          />
        </Panel>
      </Page>
    );
  }

  if (!branding.canWrite) {
    return (
      <Page>
        <PageHeader
          title="品牌与外观"
          description="机构管理员可以更换品牌色与系统显示名；其他角色只能查看当前效果。"
        />
        <Panel>
          <StateBlock
            title="没有修改品牌设置的权限"
            hint="需要机构管理员或 TENANT_BRANDING_WRITE 权限；请联系机构管理员开通。"
          />
        </Panel>
      </Page>
    );
  }

  const changed =
    seed !== branding.seed ||
    (displayName.trim() || null) !== (branding.displayName ?? null);

  async function uploadLogo(file: File, kind: "WIDE" | "MARK") {
    setLogoError("");
    if (file.size > 204800) {
      setLogoError("Logo 文件不能超过 200KB。");
      return;
    }
    if (file.type !== "image/png" && file.type !== "image/jpeg") {
      setLogoError("首版只支持 PNG 或 JPEG；SVG、GIF 暂不接受。");
      return;
    }
    setLogoBusy(kind);
    try {
      const token = await csrf();
      const form = new FormData();
      form.append("file", file);
      const response = await fetch(
        `/api/tenant/branding/logo?kind=${encodeURIComponent(kind)}`,
        {
          method: "POST",
          credentials: "same-origin",
          headers: { [token.headerName!]: token.token! },
          body: form,
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (response.status === 409) {
        setLogoError("对象存储尚未配置，暂时不能上传 Logo；请联系平台运营。");
        return;
      }
      if (response.status === 400) {
        const problem = (await response.json().catch(() => null)) as {
          detail?: string;
        } | null;
        setLogoError(problem?.detail ?? "Logo 不符合规格，请检查尺寸与比例。");
        return;
      }
      if (!response.ok) {
        setLogoError("Logo 上传失败，请稍后重试。");
        return;
      }
      apply(normalizeBranding(await response.json()));
      message.success(
        kind === "WIDE" ? "横版 Logo 已更新。" : "方形 Logo 已更新。",
      );
    } catch (failure) {
      setLogoError(
        failure instanceof Error ? failure.message : "Logo 上传失败，请重试。",
      );
    } finally {
      setLogoBusy(null);
    }
  }

  async function save() {
    setSaving(true);
    setError("");
    try {
      const token = await csrf();
      const response = await fetch("/api/tenant/branding", {
        method: "PUT",
        credentials: "same-origin",
        headers: {
          "Content-Type": "application/json",
          [token.headerName!]: token.token!,
          "If-Match": String(branding.version),
        },
        body: JSON.stringify({
          displayName: displayName.trim() || null,
          seed,
          expectedVersion: branding.version,
        }),
      });
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (response.status === 403) {
        setError("当前账号没有修改品牌设置的权限。");
        return;
      }
      if (response.status === 409) {
        setError("品牌已被其他人修改，请刷新页面后重试。");
        return;
      }
      if (!response.ok) {
        const problem = (await response.json().catch(() => null)) as {
          detail?: string;
        } | null;
        setError(problem?.detail ?? "品牌保存失败，请稍后重试。");
        return;
      }
      const next = normalizeBranding(await response.json());
      apply(next);
      message.success("品牌已保存并立即生效。");
    } catch (failure) {
      setError(
        failure instanceof Error ? failure.message : "品牌保存失败，请重试。",
      );
    } finally {
      setSaving(false);
    }
  }

  return (
    <Page>
      <PageHeader
        title="品牌与外观"
        description="只更换品牌色与系统显示名。布局、字体、圆角和状态色由平台锁定；保存后立即生效，并保留最近版本供平台回滚。"
        actions={
          <div className={styles.actions}>
            <Button
              onClick={() => void save()}
              loading={saving}
              disabled={!changed}
            >
              保存
            </Button>
          </div>
        }
      />

      <Panel head={<h2 className={styles.sectionTitle}>效果预览</h2>}>
        <div className={styles.section}>
          <div className={styles.preview}>
            <button type="button" className={styles.primarySample}>
              主按钮
            </button>
            <span className={styles.navSample}>当前导航</span>
            <a href="#branding-preview" className={styles.linkSample}>
              文字链接
            </a>
          </div>
          <p className={styles.sectionHint}>
            预览使用服务端当前令牌；保存后整站按钮、链接、选中态和焦点环一起更新。
            品牌色不会用于状态标签、错误提示或危险按钮。
          </p>
          {branding.adjusted ? (
            <Alert
              type="info"
              showIcon
              title="当前品牌色已为可读性做过调整，原始值保留在平台审计中。"
            />
          ) : null}
        </div>
      </Panel>

      <Panel head={<h2 className={styles.sectionTitle}>品牌色</h2>}>
        <div className={styles.section}>
          <p className={styles.sectionHint}>
            从平台预设中选择。预设均已通过白底对比度与焦点环检查；自定义取色在后续版本开放。
          </p>
          <div className={styles.presets} role="radiogroup" aria-label="品牌色">
            {branding.presets.map((preset) => (
              <button
                key={preset.seed}
                type="button"
                role="radio"
                aria-checked={seed === preset.seed}
                className={`${styles.preset} ${seed === preset.seed ? styles.presetActive : ""}`}
                onClick={() => setSeed(preset.seed)}
              >
                <span
                  className={styles.swatch}
                  style={{ background: preset.seed }}
                  aria-hidden="true"
                />
                {preset.name}
              </button>
            ))}
          </div>
        </div>
      </Panel>

      <Panel head={<h2 className={styles.sectionTitle}>系统显示名</h2>}>
        <div className={styles.section}>
          <p className={styles.sectionHint}>
            显示在侧栏、登录后页头和浏览器标题；留空时使用租户名称「
            {session.tenantName}」。
          </p>
          <Input
            className={styles.textField}
            value={displayName}
            maxLength={60}
            placeholder={session.tenantName}
            onChange={(event) => setDisplayName(event.target.value)}
            aria-label="系统显示名"
          />
        </div>
      </Panel>

      <Panel head={<h2 className={styles.sectionTitle}>Logo</h2>}>
        <div className={styles.section}>
          <p className={styles.sectionHint}>
            横版用于侧栏与登录后页头，方形用于折叠侧栏、favicon 与消息；首版只收
            PNG/JPEG， 单文件不超过 200KB，横版宽高比 1:1 至 4:1，方形接近 1:1。
          </p>
          <div className={styles.logoRow}>
            <div className={styles.logoPreview} aria-label="横版 Logo 预览">
              {branding.logoWideUrl ? (
                <img src={branding.logoWideUrl} alt="横版 Logo" />
              ) : (
                <span className={styles.logoMeta}>未上传</span>
              )}
            </div>
            <div>
              <label className={styles.fileButton}>
                <Button
                  loading={logoBusy === "WIDE"}
                  disabled={logoBusy !== null}
                >
                  上传横版 Logo
                </Button>
                <input
                  type="file"
                  accept="image/png,image/jpeg"
                  aria-label="上传横版 Logo"
                  disabled={logoBusy !== null}
                  onChange={(event) => {
                    const file = event.target.files?.[0];
                    event.target.value = "";
                    if (file) void uploadLogo(file, "WIDE");
                  }}
                />
              </label>
              <p className={styles.logoMeta}>
                建议透明底 PNG，显示高度 24/28/40。
              </p>
            </div>
          </div>
          <div className={styles.logoRow}>
            <div className={styles.logoPreview} aria-label="方形 Logo 预览">
              {branding.logoMarkUrl ? (
                <img
                  className={styles.logoMark}
                  src={branding.logoMarkUrl}
                  alt="方形 Logo"
                />
              ) : (
                <span className={styles.logoMeta}>未上传</span>
              )}
            </div>
            <div>
              <label className={styles.fileButton}>
                <Button
                  loading={logoBusy === "MARK"}
                  disabled={logoBusy !== null}
                >
                  上传方形 Logo
                </Button>
                <input
                  type="file"
                  accept="image/png,image/jpeg"
                  aria-label="上传方形 Logo"
                  disabled={logoBusy !== null}
                  onChange={(event) => {
                    const file = event.target.files?.[0];
                    event.target.value = "";
                    if (file) void uploadLogo(file, "MARK");
                  }}
                />
              </label>
              <p className={styles.logoMeta}>
                1:1，最小 32px；用于折叠侧栏与 favicon。
              </p>
            </div>
          </div>
          {logoError ? <Alert type="error" showIcon title={logoError} /> : null}
          <p className={styles.logoMeta}>
            SVG、GIF 与单色版暂未开放；Logo 与品牌色保存后使用同一版本历史。
          </p>
        </div>
      </Panel>

      {error ? <Alert type="error" showIcon title={error} /> : null}
    </Page>
  );
}
