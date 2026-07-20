const FEATURES = [
  {
    icon: '💎', color: 'rgba(255,92,0,0.07)',
    title: 'AI 经验萃取',
    desc: '上传对话录音或文档，AI 自动提炼经验颗粒——13 层清洗管道 + 对抗验证，从原始对话中提取专家思考、标准话术、常见错误、适用条件。',
  },
  {
    icon: '💬', color: 'rgba(37,99,235,0.07)',
    title: 'AI 专家对话',
    desc: '遇到问题随时向 AI 专家请教。每个回答可溯源——来自哪条颗粒、匹配度多高。不是黑盒 AI，是可解释的知识检索。',
  },
  {
    icon: '🎯', color: 'rgba(22,163,74,0.07)',
    title: '实战对练',
    desc: 'AI 扮演客户，模拟真实场景。每次对练即时评分——哪里说得好、哪里需改进。新人入职第一天就能上手练习。',
  },
];

export function Features() {
  return (
    <section style={{ maxWidth: 1160, margin: '0 auto', padding: '100px 40px' }}>
      <div className="eyebrow">What We Do</div>
      <h2 className="section-title">经验萃取 · AI 对话 · 实战对练</h2>
      <p className="section-sub" style={{ marginBottom: 48 }}>
        三件事，一个平台。把专家的隐性知识变成团队的显性能力。
      </p>

      <div style={{
        display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))',
        gap: 20,
      }}>
        {FEATURES.map((f, i) => (
          <div key={i} className="card" style={{ padding: '32px 28px' }}>
            <div style={{
              width: 48, height: 48, borderRadius: 14,
              background: f.color, display: 'flex',
              alignItems: 'center', justifyContent: 'center',
              fontSize: 24, marginBottom: 20,
            }}>
              {f.icon}
            </div>
            <h3 style={{ fontSize: 17, fontWeight: 700, marginBottom: 10, color: 'var(--fg-high)' }}>
              {f.title}
            </h3>
            <p style={{ fontSize: 13, color: 'var(--fg-mid)', lineHeight: 1.7 }}>
              {f.desc}
            </p>
          </div>
        ))}
      </div>
    </section>
  );
}
