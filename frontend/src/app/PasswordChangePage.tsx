import { ChangePasswordGate } from "../features/identity/ChangePasswordGate";
import styles from "./auth.module.css";

/**
 * 首次登录或被平台重置后的强制改密页。
 *
 * 改密会撤销当前会话，所以成功与失败都回到登录页，不假装还能继续操作。
 */
export function PasswordChangePage({
  onChanged,
  onSessionLost,
}: {
  onChanged: () => void;
  onSessionLost: () => void;
}) {
  return (
    <div className={styles.page}>
      <div className={styles.gateCard}>
        <h1 className={styles.gateTitle}>请先设置新密码</h1>
        {/* 说明与规则由 ChangePasswordGate 统一渲染，这里再写一遍会重复两段同样的文字。 */}
        <ChangePasswordGate
          variant="modal"
          onChanged={onChanged}
          onSessionLost={onSessionLost}
        />
      </div>
    </div>
  );
}
