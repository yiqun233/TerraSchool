import { useState } from "react";
import { Input } from "antd";

/** 一次录入的草稿：只有这两个字段是逐字符输入的，其余由主组件持有。 */
export type CustomerDraft = { name: string; phone: string };

/**
 * 客户姓名与电话输入。
 *
 * 这两个输入是页面上唯一“每敲一个字就变”的状态。放在主组件里会导致整块重渲染
 * （本人客户表、公海表、提醒表都要重算），所以把输入状态收进本组件：主组件只通过
 * `onDraftChange` 拿到同步后的草稿引用，打字不再触发页面重渲染。
 *
 * 提交成功后主组件通过改变 `resetKey` 重挂载本组件来清空输入，而不是把值再传回来。
 */
export function CustomerCreateFields({
  disabled,
  onDraftChange,
  onEdit,
}: {
  disabled: boolean;
  onDraftChange: (draft: CustomerDraft) => void;
  onEdit: () => void;
}) {
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  return (
    <>
      <Input
        aria-label="客户姓名"
        placeholder="客户姓名"
        maxLength={50}
        value={name}
        disabled={disabled}
        onChange={(event) => {
          const value = event.target.value;
          setName(value);
          // 编辑即取消上一次的重复确认，和拆分前的行为一致。
          onEdit();
          onDraftChange({ name: value, phone });
        }}
        style={{ width: 150 }}
      />
      <Input
        aria-label="客户电话"
        placeholder="联系电话"
        maxLength={30}
        value={phone}
        disabled={disabled}
        onChange={(event) => {
          const value = event.target.value;
          setPhone(value);
          onEdit();
          onDraftChange({ name, phone: value });
        }}
        style={{ width: 170 }}
      />
    </>
  );
}
