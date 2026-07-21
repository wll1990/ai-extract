'use client';

import { useEffect, useState } from 'react';
import { fetchRecommendedQuestions, type SkillDetail } from '@/lib/api/skill';

const SCENE_EMOJIS: Record<string, string> = {
  '价格谈判': '💰', '竞品对比': '🤝', '异议处理': '🎯',
  '决策推进': '🚀', '需求挖掘': '🔍', '方案演示': '📊',
  '客户维护': '💝', '催单逼单': '⚡', '破冰建立信任': '🧊',
  '行业研究': '📈', '公司分析': '🏢', '估值定价': '💎',
  '交易决策': '📉', '风险控制': '🛡️', '宏观判断': '🌐',
};
const getEmoji = (tag: string) => SCENE_EMOJIS[tag] || '💡';

interface ChatEntryProps {
  skill: SkillDetail;
  onQuestionClick: (question: string) => void;
  showQuestions?: boolean;
  showSceneTags?: boolean;
}

export function ChatEntry({ skill, onQuestionClick, showQuestions = true, showSceneTags = true }: ChatEntryProps) {
  const [questions, setQuestions] = useState<string[]>([]);
  const name = skill.displayName || skill.ownerName || '专家';
  const initial = name[0];

  useEffect(() => {
    fetchRecommendedQuestions(skill.id)
      .then(setQuestions)
      .catch(() => {});
  }, [skill.id]);

  return (
    <div style={{
      display: 'flex', flexDirection: 'column', alignItems: 'center',
      justifyContent: 'center', padding: '60px 24px', textAlign: 'center',
      flex: 1,
    }}>
      {/* Avatar */}
      {skill.avatarUrl ? (
        <img
          src={skill.avatarUrl} alt={name}
          style={{
            width: 72, height: 72, borderRadius: 20, objectFit: 'cover',
            boxShadow: '0 10px 10px 0 rgba(0,0,0,0.1), 0 4px 4px -2px rgba(0,0,0,0.1)',
            marginBottom: 20,
          }}
        />
      ) : (
        <div style={{
          width: 72, height: 72, borderRadius: 20,
          background: 'linear-gradient(135deg, var(--s12), var(--tangerine))',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          color: '#fff', fontSize: 30, fontWeight: 800,
          boxShadow: '0 10px 10px 0 rgba(0,0,0,0.1), 0 4px 4px -2px rgba(0,0,0,0.1), inset 0 1px 2px 0 rgba(255,255,255,0.5)',
          marginBottom: 20,
        }}>
          {initial}
        </div>
      )}

      <h1 style={{ fontSize: 22, fontWeight: 800, marginBottom: 6, color: 'var(--fg-high)' }}>
        {name}
      </h1>
      {skill.ownerTitle && (
        <p style={{ fontSize: 13, color: 'var(--fg-mid)', marginBottom: 16 }}>
          {skill.ownerTitle}{skill.department ? ` · ${skill.department}` : ''}
        </p>
      )}

      {skill.openingMessage && (
        <p style={{
          fontSize: 14, color: 'var(--fg-mid)', lineHeight: 1.7,
          maxWidth: 460, marginBottom: 24, background: 'var(--s3)',
          padding: '14px 20px', borderRadius: '18px 18px 18px 6px',
        }}>
          {skill.openingMessage}
        </p>
      )}

      {/* Scene tags — 3 列可点击卡片 */}
      {showSceneTags && skill.sceneTags && skill.sceneTags.length > 0 && (
        <div style={{ maxWidth: 480, width: '100%', marginBottom: 24 }}>
          <p style={{ fontSize: 11, color: 'var(--fg-dim)', marginBottom: 10 }}>
            💡 擅长领域 · 点击即可开始
          </p>
          <div style={{
            display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 8,
          }}>
            {skill.sceneTags.slice(0, 6).map(tag => (
              <button key={tag.tag} onClick={() => onQuestionClick(`聊聊${tag.tag}方面的经验？`)}
                style={{
                  padding: '12px 8px', borderRadius: 12,
                  border: '1px solid var(--border-subtle)',
                  background: 'var(--surface)',
                  cursor: 'pointer', textAlign: 'center',
                  fontFamily: 'inherit',
                  transition: 'all 0.2s ease',
                }}
                onMouseEnter={(e) => {
                  e.currentTarget.style.transform = 'translateY(-2px)';
                  e.currentTarget.style.boxShadow = '0 4px 12px rgba(0,0,0,0.08)';
                  e.currentTarget.style.borderColor = 'var(--tangerine)';
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.transform = 'translateY(0)';
                  e.currentTarget.style.boxShadow = 'none';
                  e.currentTarget.style.borderColor = 'var(--border-subtle)';
                }}
              >
                <div style={{ fontSize: 22, marginBottom: 4 }}>{getEmoji(tag.tag)}</div>
                <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--fg-high)' }}>{tag.tag}</div>
                {(tag.count || 0) > 0 && (
                  <div style={{ fontSize: 10, color: 'var(--fg-dim)', marginTop: 2 }}>
                    {tag.count} 条锦囊
                  </div>
                )}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Recommended questions */}
      {showQuestions && questions.length > 0 && (
        <div style={{ maxWidth: 420, width: '100%' }}>
          <p style={{ fontSize: 11, color: 'var(--fg-dim)', marginBottom: 10 }}>
            试试这些问题
          </p>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {questions.map((q, i) => (
              <button
                key={i}
                onClick={() => onQuestionClick(q)}
                style={{
                  width: '100%', padding: '12px 16px', borderRadius: 12,
                  border: '1px solid var(--border-subtle)',
                  background: 'var(--surface)', cursor: 'pointer',
                  fontSize: 13, color: 'var(--fg-high)', textAlign: 'left',
                  fontFamily: 'inherit',
                  display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                  transition: 'all 0.2s cubic-bezier(0.4, 0, 0.2, 1)',
                  transform: 'scale(1)',
                }}
                onMouseEnter={(e) => {
                  e.currentTarget.style.background = 'var(--s3)';
                  e.currentTarget.style.borderColor = 'var(--tangerine)';
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.background = 'var(--surface)';
                  e.currentTarget.style.borderColor = 'var(--border-subtle)';
                }}
                onMouseDown={(e) => { e.currentTarget.style.transform = 'scale(0.97)'; }}
                onMouseUp={(e) => { e.currentTarget.style.transform = 'scale(1)'; }}
              >
                <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <span style={{ fontSize: 16 }}>💬</span>
                  <span>{q}</span>
                </span>
                <span style={{
                  fontSize: 14, color: 'var(--fg-dim)',
                  transition: 'transform 0.2s ease-out, opacity 0.2s',
                  opacity: 0.5,
                }} className="rec-q-arrow">→</span>
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
