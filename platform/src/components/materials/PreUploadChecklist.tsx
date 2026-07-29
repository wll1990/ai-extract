'use client';

interface Props {
  read: boolean;
  onMarkRead: () => void;
  onReset: () => void;
}

const COLUMNS = [
  {
    emoji: '✅',
    title: '效果好',
    items: [
      { icon: '🗣️', text: '真实对话', sub: '微信聊天/通话转写' },
      { icon: '📝', text: '复盘笔记', sub: '实战总结/战报/心得' },
      { icon: '🎙️', text: '访谈实录', sub: '一问一答结构化对话' },
    ],
  },
  {
    emoji: '❌',
    title: '不要传',
    items: [
      { icon: '📚', text: '培训课件', sub: '通用理论知识' },
      { icon: '📋', text: '产品手册', sub: '纯功能列表文档' },
      { icon: '📢', text: '营销文案', sub: '推广/促销内容' },
      { icon: '🤖', text: 'AI 虚构内容', sub: '非真人实战经验' },
    ],
  },
  {
    emoji: '📏',
    title: '格式',
    items: [
      { icon: '📄', text: 'PDF / Word / TXT', sub: 'AI 自动解析' },
      { icon: '🎵', text: '录音 MP3/M4A/WAV', sub: 'AI 转写 + 萃取' },
      { icon: '🖼️', text: '图片 JPG/PNG', sub: '需补充文字说明' },
      { icon: '📏', text: '单文件 ≤ 20MB', sub: '超大文件请先压缩' },
    ],
  },
  {
    emoji: '💡',
    title: '标准',
    items: [
      { icon: '💬', text: '有对话', sub: '≥ 3 轮来回最佳' },
      { icon: '👤', text: '有角色标注', sub: '"销售:""客户:"' },
      { icon: '🇨🇳', text: '中文 ≥ 70%', sub: '模型专注中文场景' },
      { icon: '🎯', text: '第一人称', sub: '"我发现""我当时"' },
    ],
  },
];

export function PreUploadChecklist({ read, onMarkRead, onReset }: Props) {
  if (read) {
    return (
      <div style={{
        marginBottom: 16, padding: '10px 16px', borderRadius: 14,
        background: '#f0fdf4', border: '1px solid #bbf7d0',
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        fontSize: 13, color: '#166534',
      }}>
        <span>✅ 已了解上传规则</span>
        <button
          onClick={onReset}
          style={{ fontSize: 12, color: '#166534', textDecoration: 'underline', background: 'none', border: 'none', cursor: 'pointer', fontFamily: 'inherit' }}
        >
          重新查看
        </button>
      </div>
    );
  }

  return (
    <div style={{
      marginBottom: 20, padding: 20, borderRadius: 16,
      background: 'linear-gradient(135deg, #fffbeb 0%, #fef3c7 100%)',
      border: '2px solid #fcd34d',
    }}>
      <p style={{ fontSize: 14, fontWeight: 700, color: '#92400e', marginBottom: 12, display: 'flex', alignItems: 'center', gap: 6 }}>
        ⚠️ 上传前必看 — 选对素材，萃取效果提升 3 倍
      </p>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, marginBottom: 16 }}>
        {COLUMNS.map((col) => (
          <div key={col.title}>
            <p style={{ fontSize: 12, fontWeight: 700, color: '#92400e', marginBottom: 6, display: 'flex', alignItems: 'center', gap: 4 }}>
              {col.emoji} {col.title}
            </p>
            {col.items.map((item) => (
              <div key={item.text} style={{ marginBottom: 5 }}>
                <p style={{ fontSize: 12, color: '#78350f', fontWeight: 500, margin: 0 }}>
                  {item.icon} {item.text}
                </p>
                <p style={{ fontSize: 10, color: '#a8a29e', margin: '1px 0 0 18px' }}>{item.sub}</p>
              </div>
            ))}
          </div>
        ))}
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 12, paddingTop: 12, borderTop: '1px solid #fde68a' }}>
        <button
          onClick={onMarkRead}
          style={{
            padding: '8px 20px', borderRadius: 100, border: 'none', cursor: 'pointer',
            background: '#2147ff', color: '#fff', fontSize: 13, fontWeight: 600, fontFamily: 'inherit',
          }}
        >
          ✓ 我已了解，开始上传
        </button>
        <a
          href="#upload-tips"
          style={{ fontSize: 12, color: '#2147ff', textDecoration: 'underline', cursor: 'pointer' }}
        >
          📖 查看完整指南 ↓
        </a>
      </div>
    </div>
  );
}
