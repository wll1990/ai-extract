'use client';

const FLYWHEELS = [
  { emoji: '📊', title: '数据飞轮', desc: '更多对话 → 更多缺口 → Pipeline C 发现新知识 → 分身持续进化' },
  { emoji: '👤', title: '用户飞轮', desc: '反馈数据驱动颗粒质量提升 → 满意率上升 → 更多人使用' },
  { emoji: '🚀', title: '增长飞轮', desc: 'H5 分享获客 → 注册转化 → 发现更多分身 → 更多分享' },
  { emoji: '⭐', title: '供给飞轮', desc: '专家看到使用数据 → 更愿贡献 → 更多素材 → 颗粒更准' },
  { emoji: '🤖', title: 'AI 飞轮', desc: '未命中 → knowledge_gap → 聚类 → 候选颗粒 → 越用越聪明' },
];

export function Flywheel() {
  return (
    <section style={{ maxWidth: 1160, margin: '0 auto', padding: '100px 40px', textAlign: 'center' }}>
      <div className="eyebrow">Growth Engine</div>
      <h2 className="section-title">五个飞轮，越用越聪明</h2>
      <p className="section-sub" style={{ margin: '0 auto 48px' }}>
        不是静态工具。MindForge 的数据飞轮让系统持续自进化——更多使用 → 更多数据 → 更聪明 → 更多人使用。
      </p>

      <div style={{
        display: 'flex', flexWrap: 'wrap', gap: 16, justifyContent: 'center',
        maxWidth: 900, margin: '0 auto',
      }}>
        {FLYWHEELS.map((fw, i) => (
          <div key={i} style={{
            width: 160, padding: '24px 16px', textAlign: 'center',
            background: 'var(--surface)', borderRadius: 'var(--radius-3xl)',
            boxShadow: 'var(--shadow-sm)',
          }}>
            <div style={{ fontSize: 32, marginBottom: 10 }}>{fw.emoji}</div>
            <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--fg-high)', marginBottom: 6 }}>
              {fw.title}
            </div>
            <div style={{ fontSize: 11, color: 'var(--fg-low)', lineHeight: 1.6 }}>
              {fw.desc}
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}
