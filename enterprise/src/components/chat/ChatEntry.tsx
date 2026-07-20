'use client';

import { useEffect, useState } from 'react';
import { fetchRecommendedQuestions, type SkillDetail } from '@/lib/api/skill';

interface ChatEntryProps {
  skill: SkillDetail;
  onQuestionClick: (question: string) => void;
}

export function ChatEntry({ skill, onQuestionClick }: ChatEntryProps) {
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

      {/* Scene tags */}
      {skill.sceneTags && skill.sceneTags.length > 0 && (
        <div style={{
          display: 'flex', flexWrap: 'wrap', gap: 6,
          justifyContent: 'center', marginBottom: 24,
        }}>
          {skill.sceneTags.slice(0, 8).map(tag => (
            <span key={tag.tag} style={{
              padding: '4px 12px', borderRadius: 100,
              background: 'var(--s3)', fontSize: 11,
              color: 'var(--fg-mid)',
            }}>
              {tag.tag}
            </span>
          ))}
        </div>
      )}

      {/* Recommended questions */}
      {questions.length > 0 && (
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
                  width: '100%', padding: '10px 18px', borderRadius: 100,
                  border: '1.5px solid var(--border-subtle)',
                  background: 'var(--surface)', cursor: 'pointer',
                  fontSize: 13, color: 'var(--fg-high)', textAlign: 'left',
                  transition: 'all 0.15s', fontFamily: 'inherit',
                }}
                onMouseEnter={(e) => {
                  e.currentTarget.style.background = 'var(--s3)';
                  e.currentTarget.style.borderColor = 'var(--tangerine)';
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.background = 'var(--surface)';
                  e.currentTarget.style.borderColor = 'var(--border-subtle)';
                }}
              >
                {q}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
