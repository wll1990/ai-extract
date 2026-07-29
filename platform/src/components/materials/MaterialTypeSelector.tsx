'use client';

interface Props {
  value: string;
  onChange: (v: string) => void;
}

const TYPES = [
  {
    key: 'dialogue',
    icon: '💬',
    label: '对话记录',
    desc: '销售与客户的真实对话过程',
    recommended: true,
  },
  {
    key: 'monologue',
    icon: '✍️',
    label: '经验独白',
    desc: '个人复盘笔记或经验分享',
  },
  {
    key: 'interview',
    icon: '🎙️',
    label: '访谈实录',
    desc: '萃取师一对一结构化访谈',
  },
];

export function MaterialTypeSelector({ value, onChange }: Props) {
  return (
    <div style={{ marginBottom: 20 }}>
      <p style={{ fontSize: 13, fontWeight: 600, color: '#10162f', marginBottom: 8 }}>素材类型</p>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 10 }}>
        {TYPES.map((t) => {
          const selected = value === t.key;
          return (
            <button
              key={t.key}
              onClick={() => onChange(t.key)}
              style={{
                display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6,
                padding: '16px 12px', borderRadius: 16, border: selected ? '2px solid #2147ff' : '1px solid #e1e7ff',
                background: selected ? '#eef2ff' : '#fff',
                cursor: 'pointer', fontFamily: 'inherit', textAlign: 'center',
                transition: 'all 0.2s', position: 'relative',
                boxShadow: selected ? '0 4px 16px rgba(33,71,255,0.12)' : undefined,
              }}
              onMouseEnter={(e) => { if (!selected) { e.currentTarget.style.borderColor = '#cdd7ff'; e.currentTarget.style.boxShadow = '0 2px 8px rgba(0,0,0,0.04)'; } }}
              onMouseLeave={(e) => { if (!selected) { e.currentTarget.style.borderColor = '#e1e7ff'; e.currentTarget.style.boxShadow = ''; } }}
            >
              {t.recommended && (
                <span style={{
                  position: 'absolute', top: 6, right: 8,
                  fontSize: 10, color: '#f59e0b', fontWeight: 600,
                  background: '#fffbeb', padding: '1px 6px', borderRadius: 100,
                }}>
                  推荐
                </span>
              )}
              <span style={{ fontSize: 28 }}>{t.icon}</span>
              <span style={{ fontSize: 13, fontWeight: 600, color: '#10162f' }}>{t.label}</span>
              <span style={{ fontSize: 11, color: '#747f9e', lineHeight: 1.4 }}>{t.desc}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
