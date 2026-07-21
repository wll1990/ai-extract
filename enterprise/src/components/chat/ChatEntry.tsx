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
  onSceneTagClick?: (tag: string) => void;
  activeSceneTag?: string;
  showQuestions?: boolean;
  showSceneTags?: boolean;
  mode?: string;
}

export function ChatEntry({
  skill, onQuestionClick, onSceneTagClick, activeSceneTag,
  showQuestions = true, showSceneTags = true, mode = 'qa',
}: ChatEntryProps) {
  const [questions, setQuestions] = useState<string[]>([]);
  const [questionsLoading, setQuestionsLoading] = useState(false);
  const [questionsBatch, setQuestionsBatch] = useState(1);
  const BATCH_QUESTIONS = 4;
  const name = skill.displayName || skill.ownerName || '专家';
  const initial = name[0];

  const loadQuestions = (sceneTag?: string) => {
    setQuestionsLoading(true);
    fetchRecommendedQuestions(skill.id, sceneTag)
      .then(setQuestions)
      .catch((e) => { console.error('[ChatEntry] fetchRecommendedQuestions failed', e); })
      .finally(() => setQuestionsLoading(false));
  };

  useEffect(() => {
    if (showQuestions) loadQuestions();
  }, [skill.id, showQuestions]);

  // 当 activeSceneTag 变化时重新加载推荐问题 + 重置展开状态
  useEffect(() => {
    if (showQuestions && activeSceneTag !== undefined) {
      loadQuestions(activeSceneTag);
      setQuestionsBatch(1);
    }
  }, [activeSceneTag]);

  return (
    <div style={{
      display: 'flex', flexDirection: 'column', alignItems: 'center',
      justifyContent: 'flex-start', padding: '32px 24px 60px', textAlign: 'center',
      flex: 1,
    }}>
      {/* Avatar */}
      <div className="animate-stagger-1">
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
      </div>

      <div className="animate-stagger-2">
        <h1 style={{ fontSize: 22, fontWeight: 800, margin: '0 0 6px', color: 'var(--fg-high)' }}>
          {name}
        </h1>
        {skill.ownerTitle && (
          <p style={{ fontSize: 13, color: 'var(--fg-mid)', marginBottom: 16 }}>
            {skill.ownerTitle}{skill.department ? ` · ${skill.department}` : ''}
          </p>
        )}
      </div>

      {/* Opening message = "TA 这样说" */}
      {skill.openingMessage && (
        <div className="animate-stagger-3" style={{ maxWidth: 460, width: '100%', marginBottom: 24 }}>
          <p style={{
            fontSize: 11, color: 'var(--fg-dim)', marginBottom: 6,
            textAlign: 'center',
          }}>
            TA 这样说
          </p>
          <p style={{
            fontSize: 14, color: 'var(--fg-mid)', lineHeight: 1.7,
            padding: '14px 20px', borderRadius: '18px 18px 18px 6px',
            background: mode === 'qa' ? '#fef9f0' : mode === 'talk' ? '#f5f3ff' : '#f0fdf6',
            borderLeft: mode === 'qa' ? '2px solid rgba(245,158,11,0.2)' : mode === 'talk' ? '2px solid rgba(99,102,241,0.2)' : '2px solid rgba(16,185,129,0.2)',
            transition: 'background 0.4s ease, border-color 0.4s ease',
            margin: 0,
            textAlign: 'center',
          }}>
            {skill.openingMessage}
          </p>
        </div>
      )}

      {/* 擅长领域 — 横向滚动 pill 条 (QA only) */}
      {showSceneTags && skill.sceneTags && skill.sceneTags.length > 0 && (
        <div className="animate-stagger-4" style={{ maxWidth: 520, width: '100%', marginBottom: 24 }}>
          <p style={{ fontSize: 11, color: 'var(--fg-dim)', marginBottom: 8 }}>
            擅长领域
          </p>
          <div style={{
            display: 'flex', gap: 8, overflowX: 'auto', paddingBottom: 8,
            scrollbarWidth: 'none', msOverflowStyle: 'none',
            justifyContent: 'center', flexWrap: 'nowrap',
          }}>
            {skill.sceneTags.map(tag => {
              const isActive = activeSceneTag === tag.tag;
              return (
              <button
                key={tag.tag}
                onClick={() => onSceneTagClick?.(tag.tag)}
                style={{
                  flexShrink: 0, padding: '7px 16px', borderRadius: 100,
                  border: isActive ? '2px solid var(--tangerine)' : '1px solid var(--border-subtle)',
                  background: isActive ? 'rgba(255,92,0,0.06)' : 'var(--surface)',
                  cursor: 'pointer', textAlign: 'center',
                  fontFamily: 'inherit', fontSize: 13, fontWeight: 600,
                  color: isActive ? 'var(--tangerine)' : 'var(--fg-high)',
                  transition: 'all 0.2s ease',
                  transform: isActive ? 'translateY(-1px)' : 'none',
                  boxShadow: isActive ? '0 2px 8px rgba(255,92,0,0.12)' : 'none',
                  whiteSpace: 'nowrap',
                  display: 'flex', alignItems: 'center', gap: 6,
                }}
                onMouseEnter={(e) => {
                  if (!isActive) {
                    e.currentTarget.style.borderColor = 'var(--tangerine)';
                    e.currentTarget.style.background = 'rgba(255,92,0,0.04)';
                  }
                }}
                onMouseLeave={(e) => {
                  if (!isActive) {
                    e.currentTarget.style.borderColor = 'var(--border-subtle)';
                    e.currentTarget.style.background = 'var(--surface)';
                  }
                }}
              >
                <span style={{ fontSize: 15 }}>{getEmoji(tag.tag)}</span>
                {tag.tag}
                {(tag.count || 0) > 0 && (
                  <span style={{
                    fontSize: 10, color: isActive ? 'var(--tangerine)' : 'var(--fg-dim)',
                    background: isActive ? 'rgba(255,92,0,0.08)' : 'var(--s3)',
                    padding: '1px 6px', borderRadius: 10, fontWeight: 500,
                  }}>
                    {tag.count}
                  </span>
                )}
              </button>
              );
            })}
          </div>
        </div>
      )}

      {/* 精选话题 — recommended questions (QA only) */}
      {showQuestions && (
        <div className="animate-stagger-5" style={{ maxWidth: 420, width: '100%' }}>
          <p style={{ fontSize: 11, color: 'var(--fg-dim)', marginBottom: 6 }}>
            精选话题
          </p>
          <p style={{
            fontSize: 11, color: 'var(--fg-dim)', marginBottom: 10, lineHeight: 1.5,
          }}>
            {activeSceneTag
              ? `以下是「${activeSceneTag}」领域的常见问题`
              : '试试大家常问的，或先选择一个擅长领域'}
          </p>
          {questionsLoading ? (
            <div style={{ fontSize: 13, color: 'var(--fg-dim)', padding: 12 }}>加载中…</div>
          ) : questions.length > 0 ? (() => {
            const visibleQCount = Math.min(questionsBatch * BATCH_QUESTIONS, questions.length);
            const visibleQuestions = questions.slice(0, visibleQCount);
            const hasMoreQ = visibleQCount < questions.length;
            const canCollapseQ = questionsBatch > 1;
            return (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              {visibleQuestions.map((q, i) => (
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
              {(hasMoreQ || canCollapseQ) && (
                <div style={{ display: 'flex', gap: 8, justifyContent: 'center', marginTop: 4 }}>
                  {hasMoreQ && (
                    <button
                      onClick={() => setQuestionsBatch(prev => prev + 1)}
                      style={{
                        background: 'none', border: 'none', cursor: 'pointer',
                        fontSize: 12, color: 'var(--tangerine)', fontFamily: 'inherit',
                        fontWeight: 500,
                      }}
                    >
                      展开更多 →
                    </button>
                  )}
                  {canCollapseQ && (
                    <button
                      onClick={() => setQuestionsBatch(1)}
                      style={{
                        background: 'none', border: 'none', cursor: 'pointer',
                        fontSize: 12, color: 'var(--fg-dim)', fontFamily: 'inherit',
                      }}
                    >
                      收起
                    </button>
                  )}
                </div>
              )}
            </div>
            );
          })() : (
            <div style={{ fontSize: 13, color: 'var(--fg-dim)', padding: 12 }}>暂无推荐话题</div>
          )}
        </div>
      )}
    </div>
  );
}
