import { Typography } from "antd";
import { SchoolCreator } from "./SchoolCreator";
import styles from "./OnboardingGuide.module.css";

/**
 * 首次使用引导：机构还没有任何学校时展示。
 *
 * 是否展示由事实决定——账号的组织读取范围为空且具备组织读取权限——不新增“已引导”标记，
 * 也就不会出现“标记说引导过、实际没有学校”的不一致。建好第一所学校后本页自然消失。
 */
export function OnboardingGuide({
  onSchoolCreated,
}: {
  onSchoolCreated: () => void;
}) {
  return (
    <section className={styles.guide} aria-label="首次使用引导">
      <Typography.Title level={3} className={styles.title}>
        先建一所学校
      </Typography.Title>
      <p className={styles.lead}>
        这个机构还没有学校。学校是起点：校区、部门、员工、班级和报名都挂在它下面。
        建好之后，其余业务菜单才会真正有内容。
      </p>
      <ol className={styles.steps}>
        <li className={styles.step}>
          <span className={styles.index}>1</span>
          <div className={styles.body}>
            <b>建学校</b>
            <p>填写客户实际使用的学校名称。之后可以再建校区与部门。</p>
            <SchoolCreator onClosed={onSchoolCreated} />
          </div>
        </li>
        <li className={`${styles.step} ${styles.pending}`}>
          <span className={styles.index}>2</span>
          <div className={styles.body}>
            <b>建校区与部门</b>
            <p>学校建好后，在“组织目录”里继续添加校区和部门。</p>
          </div>
        </li>
        <li className={`${styles.step} ${styles.pending}`}>
          <span className={styles.index}>3</span>
          <div className={styles.body}>
            <b>添加员工并分配角色</b>
            <p>在“员工管理”里建员工、分配角色，再按岗位授予学校范围。</p>
          </div>
        </li>
      </ol>
      <p className={styles.note}>
        第 2、3 步要等学校建好后才能进行；本页会在建校完成后自动消失。
      </p>
    </section>
  );
}
