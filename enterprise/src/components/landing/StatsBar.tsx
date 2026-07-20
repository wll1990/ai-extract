'use client';

import { useEffect, useState } from 'react';
import { fetchPublicStats } from '@/lib/api/skill';

interface Stats {
  publishedSkills: number;
  totalGrains: number;
  totalConversations: number;
}

export function StatsBar() {
  const [stats, setStats] = useState<Stats | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    fetchPublicStats()
      .then(setStats)
      .catch(() => setError(true));
  }, []);

  // API 失败时静默隐藏，不阻塞页面
  if (error || !stats) return null;

  const items = [
    { value: stats.publishedSkills.toLocaleString(), label: '已发布 AI 分身' },
    { value: stats.totalGrains.toLocaleString(), label: '经验颗粒' },
    { value: stats.totalConversations.toLocaleString(), label: '累计对话' },
  ];

  return (
    <div style={{
      display: 'flex', gap: 1, background: 'var(--border-subtle)',
      borderRadius: 20, overflow: 'hidden', maxWidth: 720,
      margin: '0 auto',
    }}>
      {items.map((item, i) => (
        <div key={i} style={{
          flex: 1, textAlign: 'center', padding: '26px 18px',
          background: 'var(--surface)',
        }}>
          <div style={{
            fontSize: 28, fontWeight: 800, color: 'var(--fg-high)',
            letterSpacing: '-0.03em',
          }}>
            {item.value}
          </div>
          <div style={{
            fontSize: 12, color: 'var(--fg-low)', marginTop: 4,
          }}>
            {item.label}
          </div>
        </div>
      ))}
    </div>
  );
}
