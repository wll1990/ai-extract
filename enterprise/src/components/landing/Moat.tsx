const MOATS = [
  {
    color: '#ff5c00', emoji: '🔧',
    title: '工程复杂度',
    desc: '13 层清洗管道 + 9 步萃取师管道 + 自进化引擎。仅 AI prompt 模板就有 30+ 个，每个都经过反复调优。追平成本：12-18 个月 + 核心工程团队。',
  },
  {
    color: '#2563eb', emoji: '📚',
    title: '领域知识编码',
    desc: '每个领域需定义角色体系、场景标签、准入规则、质量评分标准、审核校验规则。不是"改一行 prompt"就能加领域。',
  },
  {
    color: '#16a34a', emoji: '📈',
    title: '数据飞轮先发优势',
    desc: '6 个月积累 2000+ 颗粒后，切换成本远超订阅成本。客户不会从零开始重建所有 AI 分身和对话历史。',
  },
  {
    color: '#8b5cf6', emoji: '⚡',
    title: '飞轮叠加效应',
    desc: '五个飞轮不是并行——是串行加速。每个节点的推动力来自前一个节点的积累，竞品无法跳过时间。',
  },
  {
    color: '#d97706', emoji: '🌐',
    title: '双边平台效应',
    desc: '专家提供经验（供给），员工消费经验（需求）。平台价值随着两端用户增长呈网络效应递增。',
  },
];

export function Moat() {
  return (
    <section style={{ maxWidth: 1160, margin: '0 auto', padding: '60px 40px' }}>
      <div className="eyebrow">Competitive Moat</div>
      <h2 className="section-title">为什么难以复制</h2>
      <p className="section-sub" style={{ marginBottom: 48 }}>
        壁垒不是"我们有 AI"，而是"竞争对手需要花多长时间、多少钱才能追到这个水平"。
      </p>

      <div style={{
        display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))',
        gap: 16,
      }}>
        {MOATS.map((m, i) => (
          <div key={i} className="card" style={{
            padding: '28px 22px', borderTop: `3px solid ${m.color}`,
          }}>
            <div style={{ fontSize: 28, marginBottom: 12 }}>{m.emoji}</div>
            <h3 style={{ fontSize: 15, fontWeight: 700, color: 'var(--fg-high)', marginBottom: 8 }}>
              {m.title}
            </h3>
            <p style={{ fontSize: 12, color: 'var(--fg-mid)', lineHeight: 1.7 }}>
              {m.desc}
            </p>
          </div>
        ))}
      </div>
    </section>
  );
}
