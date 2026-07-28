'use client';

import { useState, useEffect, useMemo } from 'react';
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

/** 根据专家标签和领域生成推荐问题 */
function generateQuestions(skill: PublicSkillInfo): string[] {
  const tags = skill.tags || [];
  const name = skill.displayName || skill.ownerName || '专家';
  const questions: string[] = [];

  if (tags.length === 0) {
    return [`${name}，能分享一个你最难忘的成单案例吗？`, `${name}，遇到客户说"太贵了"你会怎么应对？`, `向 ${name} 请教一下客户决策链怎么渗透？`];
  }

  const templates: Record<string, string[]> = {
    'B2B': [`B2B大客户怎么突破决策链？`, `多部门采购流程怎么推进？`, `${name}，你是怎么搞定企业级客户的？`],
    'SaaS': [`SaaS 产品怎么设计试用转化路径？`, `续约率怎么提升？`, `${name}，SaaS 销售最关键的指标是什么？`],
    'ERP': [`ERP 项目的关键决策人怎么找？`, `实施周期太长客户没耐心怎么办？`, `${name}，ERP 售前演示有什么技巧？`],
    '金融': [`金融客户最看重什么合规要求？`, `银行采购流程怎么加速？`, `${name}，怎么跟风控部门打交道？`],
    '政府': [`政府项目怎么找对接口人？`, `招投标评分规则怎么研究？`, `${name}，政府关系维护有什么心得？`],
    '医疗': [`医院采购委员会怎么影响？`, `医疗合规要求怎么满足？`, `${name}，主任和院长的关注点有什么不同？`],
    '制造业': [`工厂客户的 ROI 怎么算？`, `产线改造项目怎么推进？`, `${name}，制造业客户最在意什么？`],
  };

  for (const tag of tags) {
    const tmpl = templates[tag];
    if (tmpl) {
      for (const q of tmpl) {
        if (!questions.includes(q)) questions.push(q);
      }
    }
  }

  if (questions.length < 3) {
    questions.push(`${name}，能分享一个你最成功的案例吗？`);
    questions.push(`你觉得${tags[0] || '销售'}领域最大的挑战是什么？`);
    questions.push(`向 ${name} 请教一下谈判技巧`);
  }

  return questions.slice(0, 5);
}

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

  const questions = useMemo(() => generateQuestions(skill), [skill]);
  const [qIdx, setQIdx] = useState(0);

  // 每 3 秒轮换一个问题
  useEffect(() => {
    if (questions.length <= 1) return;
    const timer = setInterval(() => {
      setQIdx(prev => (prev + 1) % questions.length);
    }, 3000);
    return () => clearInterval(timer);
  }, [questions.length]);

  const currentQuestion = questions[qIdx] || `向 ${name} 请教...`;

  return (
    <Link
      href={`/chat/${skill.id}`}
      style={{
        display: 'flex', gap: 16, alignItems: 'center',
        cursor: 'pointer', textDecoration: 'none', color: 'inherit',
        padding: '14px 16px', margin: '0 -16px',
        borderRadius: 16,
        transition: 'background 0.2s, transform 0.2s',
      }}
      onMouseEnter={(e) => {
        e.currentTarget.style.background = 'var(--s3)';
        e.currentTarget.style.transform = 'translateX(4px)';
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.background = 'transparent';
        e.currentTarget.style.transform = 'translateX(0)';
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
        {skill.stats && skill.stats.conversationCount > 0 ? (
          <div style={{ fontSize: 10, color: 'var(--fg-dim)', marginTop: 4 }}>
            💬 {skill.stats.conversationCount} 次对话
            {skill.stats.satisfactionRate > 0 && <> · 👍 {skill.stats.satisfactionRate}%</>}
          </div>
        ) : (
          <div style={{ fontSize: 10, color: 'var(--fg-dim)', marginTop: 4 }}>
            {skill.grainCount} 条经验 · {skill.domain || tags.join('/')}
          </div>
        )}
      </div>

      {/* Animated question bubble */}
      <div style={{
        flex: 1, minWidth: 0,
        background: 'var(--s3)', borderRadius: '18px 18px 18px 6px',
        padding: '14px 18px', fontSize: 13, color: 'var(--fg-mid)',
        lineHeight: 1.6, position: 'relative', overflow: 'hidden',
      }}>
        <span
          key={qIdx}
          style={{
            display: 'inline',
            animation: 'fadeSlideIn 0.35s ease-out',
          }}
        >
          {currentQuestion}
        </span>
        {/* 打字光标 */}
        <span style={{
          display: 'inline-block', width: 6, height: 14,
          background: 'var(--tangerine)', borderRadius: 3,
          marginLeft: 4, verticalAlign: 'middle',
          animation: 'cursorBlink 0.8s ease-in-out infinite',
        }} />
      </div>
    </Link>
  );
}
