'use client';

import { useMemo, useState, useEffect } from 'react';
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

function generateQuestions(skill: PublicSkillInfo): string[] {
  const tags = skill.tags || [];
  const name = skill.displayName || skill.ownerName || '专家';
  if (tags.length === 0) {
    return [`${name}，能分享一个你最难忘的成单案例吗？`, `${name}，遇到客户说"太贵了"你会怎么应对？`, `向 ${name} 请教一下客户决策链怎么渗透？`];
  }
  const qs: string[] = [];
  for (const tag of tags) {
    qs.push(`${name}，你在${tag}方面有什么独到经验？`);
  }
  if (qs.length < 3) {
    qs.push(`${name}，能分享一个你最成功的案例吗？`);
    qs.push(`向 ${name} 请教一下谈判技巧`);
  }
  return qs.slice(0, 5);
}

interface Props {
  skill: PublicSkillInfo;
  index: number;
}

export function ExpertCard({ skill, index }: Props) {
  const name = skill.displayName || skill.ownerName || '专家';
  const initial = name[0];
  const tags = skill.tags || [];
  const isOrg = skill.type === 'organization';
  const avatarBg = AVATAR_COLORS[index % AVATAR_COLORS.length];
  const stats = skill.stats;
  const questions = useMemo(() => generateQuestions(skill), [skill]);
  const [qIdx, setQIdx] = useState(0);

  useEffect(() => {
    if (questions.length <= 1) return;
    const timer = setInterval(() => setQIdx(prev => (prev + 1) % questions.length), 3000);
    return () => clearInterval(timer);
  }, [questions.length]);

  return (
    <Link href={`/skill/${skill.id}`}
      style={{
        display: 'flex', gap: 16, alignItems: 'center',
        padding: 20, borderRadius: 20,
        background: 'var(--surface)',
        boxShadow: '0 2px 12px rgba(0,0,0,0.04)',
        textDecoration: 'none', color: 'inherit',
        transition: 'transform 0.2s, box-shadow 0.2s',
      }}
      onMouseEnter={e => {
        e.currentTarget.style.transform = 'translateY(-2px)';
        e.currentTarget.style.boxShadow = '0 8px 24px rgba(0,0,0,0.08)';
      }}
      onMouseLeave={e => {
        e.currentTarget.style.transform = 'translateY(0)';
        e.currentTarget.style.boxShadow = '0 2px 12px rgba(0,0,0,0.04)';
      }}
    >
      {/* Avatar */}
      {isOrg ? (
        <div style={{
          width: 56, height: 56, borderRadius: 16, flexShrink: 0,
          background: avatarBg,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontSize: 24,
        }}>🏢</div>
      ) : skill.avatarUrl ? (
        <img src={skill.avatarUrl} alt={name} style={{
          width: 56, height: 56, borderRadius: 16, objectFit: 'cover', flexShrink: 0,
        }} />
      ) : (
        <div style={{
          width: 56, height: 56, borderRadius: 16, flexShrink: 0,
          background: avatarBg,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          color: '#fff', fontSize: 24, fontWeight: 700,
        }}>{initial}</div>
      )}

      {/* Info */}
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
          <span style={{ fontSize: 15, fontWeight: 700, color: 'var(--fg-high)' }}>{name}</span>
          <span style={{
            fontSize: 10, fontWeight: 600, padding: '1px 6px', borderRadius: 4,
            background: isOrg ? '#eef2ff' : '#fef3c7',
            color: isOrg ? '#4f46e5' : '#92400e',
          }}>{isOrg ? '组织' : '个人'}</span>
          {tags.slice(0, 2).map(t => (
            <span key={t} style={{ fontSize: 10, color: 'var(--fg-low)', background: 'var(--s3)', padding: '1px 6px', borderRadius: 4 }}>{t}</span>
          ))}
        </div>
        {skill.ownerTitle && (
          <div style={{ fontSize: 12, color: 'var(--fg-mid)', marginBottom: 8 }}>
            {skill.ownerTitle}{skill.domain ? ` · ${skill.domain}` : ''}
          </div>
        )}

        {/* KPI */}
        {stats && (stats.conversationCount > 0 || stats.userCount > 0) && (
          <div style={{ display: 'flex', gap: 8, marginBottom: 8 }}>
            {stats.conversationCount > 0 && (
              <span style={{ fontSize: 11, padding: '4px 10px', borderRadius: 8, background: 'var(--s3)', color: 'var(--fg-mid)' }}>
                <b style={{ color: 'var(--fg-high)' }}>{stats.conversationCount.toLocaleString()}</b> 次对话
              </span>
            )}
            {stats.userCount > 0 && (
              <span style={{ fontSize: 11, padding: '4px 10px', borderRadius: 8, background: 'var(--s3)', color: 'var(--fg-mid)' }}>
                <b style={{ color: 'var(--fg-high)' }}>{stats.userCount.toLocaleString()}</b> 位用户
              </span>
            )}
            {stats.satisfactionRate > 0 && (
              <span style={{ fontSize: 11, padding: '4px 10px', borderRadius: 8, background: 'var(--s3)', color: 'var(--fg-mid)' }}>
                👍 <b style={{ color: 'var(--fg-high)' }}>{stats.satisfactionRate}%</b>
              </span>
            )}
          </div>
        )}

        {/* Question bubble */}
        {questions.length > 0 && (
          <div style={{
            background: 'var(--s3)', borderRadius: '18px 18px 18px 6px',
            padding: '10px 14px', fontSize: 12, color: 'var(--fg-mid)',
            lineHeight: 1.6,
          }}>
            <span key={qIdx} style={{ animation: 'fadeSlideIn 0.35s ease-out', display: 'inline' }}>
              💬 {questions[qIdx]}
            </span>
          </div>
        )}
      </div>
    </Link>
  );
}
