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
        <p className={styles.hint}>
          这是账号首次登录或被管理员重置后的第一次登录，必须先修改密码。
          新密码至少 12 位，不能与登录名或当前密码相同，也不能纯为数字或字母。
        </p>
        <ChangePasswordGate
          variant="modal"
          onChanged={onChanged}
          onSessionLost={onSessionLost}
        />
      </div>
    </div>
  );
}
