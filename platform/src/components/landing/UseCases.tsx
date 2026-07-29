const CASES = [
  {
    emoji: '💼', gradient: 'linear-gradient(135deg,#ff5c00,#ff8c40)',
    quote: '"新销售入职第一周就能和 AI 专家对练。培训成本降了 60%。"',
    title: '销售团队', tags: 'B2B · 金融 · SaaS',
  },
  {
    emoji: '📞', gradient: 'linear-gradient(135deg,#2563eb,#60a5fa)',
    quote: '"客户问的问题 AI 专家秒回，以前要查文档、问同事、等半天。"',
    title: '客户成功团队', tags: 'SaaS · 续约 · 支持',
  },
  {
    emoji: '🎓', gradient: 'linear-gradient(135deg,#16a34a,#4ade80)',
    quote: '"资深导师的咨询方法论被 AI 萃取后，新顾问随时可以请教学习。"',
    title: '教育咨询团队', tags: '学业规划 · 升学指导 · 职业发展',
  },
];

export function UseCases() {
  return (
    <section style={{ maxWidth: 1160, margin: '0 auto', padding: '100px 40px' }}>
      <div className="eyebrow">Use Cases</div>
      <h2 className="section-title">谁在用 MindForge</h2>
      <p className="section-sub" style={{ marginBottom: 48 }}>
        从销售到客服、从金融到教育——任何知识密集型团队都能用。
      </p>

      <div style={{
        display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))',
        gap: 20,
      }}>
        {CASES.map((c, i) => (
          <div key={i} className="card" style={{ padding: '28px 24px' }}>
            <div style={{
              width: 44, height: 44, borderRadius: 12,
              background: c.gradient, display: 'flex',
              alignItems: 'center', justifyContent: 'center',
              fontSize: 22, marginBottom: 16,
            }}>
              {c.emoji}
            </div>
            <div style={{
              fontSize: 13, color: 'var(--fg-high)', lineHeight: 1.7,
              fontStyle: 'italic', marginBottom: 12,
            }}>
              {c.quote}
            </div>
            <div style={{ fontSize: 13, fontWeight: 700 }}>{c.title}</div>
            <div style={{ fontSize: 11, color: 'var(--fg-dim)', marginTop: 4 }}>{c.tags}</div>
          </div>
        ))}
      </div>
    </section>
  );
}
