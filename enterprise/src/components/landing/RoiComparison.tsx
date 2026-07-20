const COMPARISONS = [
  { before: '新人上手 3 个月', after: '新人上手 2 周', change: '-83%' },
  { before: '培训依赖老员工口传心授', after: 'AI 专家 7×24 随时可用', change: '永久在线' },
  { before: '核心人才离职经验归零', after: '经验颗粒永久沉淀', change: '知识资产化' },
  { before: '一线遇到问题问同事', after: 'AI 专家秒回 + 溯源颗粒', change: '效率 10x' },
  { before: '培训效果无法量化', after: '对练即时打分 + 可追踪', change: '可量化' },
];

export function RoiComparison() {
  return (
    <section style={{ maxWidth: 1160, margin: '0 auto', padding: '60px 40px' }}>
      <div className="eyebrow">Before &amp; After</div>
      <h2 className="section-title">有了 MindForge 之后</h2>
      <p className="section-sub" style={{ marginBottom: 48 }}>
        企业知识管理的范式转移——从"人带人"到"AI 带人"。
      </p>

      <div style={{
        display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(340px, 1fr))',
        gap: 12,
      }}>
        {COMPARISONS.map((c, i) => (
          <div key={i} className="card" style={{ padding: '22px 24px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: 11, color: 'var(--fg-dim)', marginBottom: 2 }}>BEFORE</div>
                <div style={{
                  fontSize: 14, color: 'var(--fg-low)',
                  textDecoration: 'line-through',
                }}>
                  {c.before}
                </div>
              </div>
              <div style={{ fontSize: 18, color: 'var(--fg-dim)' }}>→</div>
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: 11, color: 'var(--tangerine)', marginBottom: 2 }}>AFTER</div>
                <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--fg-high)' }}>
                  {c.after}
                </div>
              </div>
              <div style={{
                padding: '4px 12px', borderRadius: 100,
                background: 'rgba(22,163,74,0.1)', color: '#16a34a',
                fontSize: 11, fontWeight: 700, whiteSpace: 'nowrap',
              }}>
                {c.change}
              </div>
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}
