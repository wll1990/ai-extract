import Link from 'next/link';
import type { PublicSkillInfo } from '@/lib/api/skill';

const AVATAR_COLORS = [
  'linear-gradient(135deg,#ff5c00,#ff8c40)',
  'linear-gradient(135deg,#2563eb,#60a5fa)',
  'linear-gradient(135deg,#16a34a,#4ade80)',
  'linear-gradient(135deg,#8b5cf6,#c084fc)',
  'linear-gradient(135deg,#d97706,#fbbf24)',
  'linear-gradient(135deg,#e11d48,#fb7185)',
  'linear-gradient(135deg,#0891b2,#22d3ee)',
  'linear-gradient(135deg,#4f46e5,#818cf8)',
];

interface ExpertRowProps {
  skill: PublicSkillInfo;
  index: number;
}

export function ExpertRow({ skill, index }: ExpertRowProps) {
  const name = skill.displayName || skill.ownerName || '专家';
  const initial = name[0];
  const avatarBg = skill.avatarUrl
    ? undefined
    : AVATAR_COLORS[index % AVATAR_COLORS.length];
  const tags = skill.tags || [];

  return (
    <Link
      href={`/chat/${skill.id}`}
      style={{
        display: 'flex', gap: 16, alignItems: 'center',
        cursor: 'pointer', textDecoration: 'none', color: 'inherit',
        padding: '12px 0',
      }}
    >
      {/* Avatar */}
      {skill.avatarUrl ? (
        <img
          src={skill.avatarUrl}
          alt={name}
          style={{
            width: 48, height: 48, borderRadius: 14, objectFit: 'cover',
            flexShrink: 0,
            boxShadow: '0 10px 10px 0 rgba(0,0,0,0.1), 0 4px 4px -2px rgba(0,0,0,0.1)',
          }}
        />
      ) : (
        <div style={{
          width: 48, height: 48, borderRadius: 14, flexShrink: 0,
          background: avatarBg, display: 'flex',
          alignItems: 'center', justifyContent: 'center',
          color: '#fff', fontSize: 20, fontWeight: 700,
          boxShadow: '0 10px 10px 0 rgba(0,0,0,0.1), 0 4px 4px -2px rgba(0,0,0,0.1), inset 0 1px 2px 0 rgba(255,255,255,0.5)',
        }}>
          {initial}
        </div>
      )}

      {/* Info column */}
      <div style={{ minWidth: 0, width: 140, flexShrink: 0 }}>
        <div style={{ fontSize: 14, fontWeight: 700 }}>{name}</div>
        {skill.ownerTitle && (
          <div style={{ fontSize: 11, color: 'var(--fg-low)', marginTop: 2 }}>
            {skill.ownerTitle}{tags.length > 0 ? ` · ${tags[0]}` : ''}
          </div>
        )}
        <div style={{ fontSize: 10, color: 'var(--fg-dim)', marginTop: 4 }}>
          {skill.grainCount} 颗粒
        </div>
      </div>

      {/* Chat bubble preview */}
      <div style={{
        flex: 1, minWidth: 0,
        background: 'var(--s3)', borderRadius: '18px 18px 18px 6px',
        padding: '12px 16px', fontSize: 13, color: 'var(--fg-mid)',
        lineHeight: 1.6, transition: 'background 0.15s',
      }}>
        {skill.openingMessage || `向 ${name} 请教...`}
      </div>
    </Link>
  );
}
