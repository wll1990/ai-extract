'use client';

import { useState, useEffect, useCallback } from 'react';
import { useRouter } from 'next/navigation';
import type { SkillDetail } from '@/lib/api/skill';
import { fetchRecommendedQuestions, getOrCreateShare } from '@/lib/api/skill';

const ORG_AVATAR_COLORS = ['#6366f1', '#8b5cf6', '#a855f7', '#d946ef'];

/* 动画定义（模块级单次注入） */
const KEYFRAME_ID = 'skill-card-keyframes';
if (typeof document !== 'undefined' && !document.getElementById(KEYFRAME_ID)) {
  const style = document.createElement('style');
  style.id = KEYFRAME_ID;
  style.textContent = `
    @keyframes pulse-ring { 0%{transform:scale(1);opacity:.5} 50%{transform:scale(2.2);opacity:0} 100%{transform:scale(1);opacity:0} }
    @keyframes cta-pulse { 0%{transform:scale(1);opacity:.6} 50%{transform:scale(1.04);opacity:0} 100%{transform:scale(1);opacity:0} }
    @keyframes spin { to{transform:rotate(360deg)} }
  `;
  document.head.appendChild(style);
}

interface Props {
  skill: SkillDetail;
}

/** 分享弹窗中的单行链接 */
function ShareLinkRow({ icon, label, desc, url, copied, onCopy }: {
  icon: string; label: string; desc: string; url: string; copied: boolean; onCopy: () => void;
}) {
  return (
    <div style={{ marginBottom: 10, textAlign: 'left' }}>
      <div style={{ fontSize: 12, fontWeight: 600, color: '#475569', marginBottom: 4 }}>
        {icon} {label} <span style={{ fontWeight: 400, color: '#94a3b8' }}>— {desc}</span>
      </div>
      <div style={{ display: 'flex', gap: 6 }}>
        <div style={{
          flex: 1, background: '#f8fafc', border: '1px solid #e2e8f0',
          borderRadius: 8, padding: '8px 12px', fontSize: 11,
          color: '#334155', fontFamily: 'monospace', wordBreak: 'break-all',
          display: 'flex', alignItems: 'center',
        }}>
          {url}
        </div>
        <button onClick={onCopy} style={{
          padding: '8px 14px', borderRadius: 8, flexShrink: 0,
          background: copied ? '#10b981' : '#6366f1', color: '#fff', border: 'none',
          cursor: 'pointer', fontSize: 12, fontWeight: 600, fontFamily: 'inherit',
        }}>
          {copied ? '✓ 已复制' : '📋'}
        </button>
      </div>
    </div>
  );
}

export function SkillCardPage({ skill }: Props) {
  const router = useRouter();

  const name = skill.displayName || skill.ownerName || '专家';
  const title = skill.ownerTitle || '';
  const isOrg = skill.type === 'organization';
  const orgMembers = skill.members || [];
  const previewMembers = orgMembers.slice(0, 4);

  // 推荐问题 — 优先缓存，null 调兜底 API，限制 5 条
  const [questions, setQuestions] = useState<string[]>(
    (skill.recommendedQuestions || []).slice(0, 5)
  );
  const [questionsLoading, setQuestionsLoading] = useState<boolean>(
    !(skill.recommendedQuestions && skill.recommendedQuestions.length > 0)
  );

  useEffect(() => {
    if (questionsLoading && skill.id) {
      fetchRecommendedQuestions(skill.id)
        .then(qs => { if (Array.isArray(qs) && qs.length > 0) setQuestions(qs.slice(0, 5)); })
        .catch(() => {})
        .finally(() => setQuestionsLoading(false));
    }
  }, [skill.id, questionsLoading]);

  // 输入 & 轮播
  const [inputValue, setInputValue] = useState('');
  const [placeholderIdx, setPlaceholderIdx] = useState(0);
  const hasInput = inputValue.trim().length > 0;

  useEffect(() => {
    if (questions.length === 0) return;
    const timer = setInterval(() => {
      setPlaceholderIdx(i => (i + 1) % questions.length);
    }, 3000);
    return () => clearInterval(timer);
  }, [questions]);

  const currentPlaceholder = questions.length > 0
    ? questions[placeholderIdx]
    : '输入你想咨询的问题...';

  const doAction = useCallback((text?: string) => {
    const q = (text || inputValue.trim());
    if (q) {
      router.push(`/chat/${skill.id}?q=${encodeURIComponent(q)}&mode=talk`);
    } else {
      router.push(`/chat/${skill.id}`);
    }
  }, [inputValue, skill.id, router]);

  const handleSend = useCallback(() => {
    if (!hasInput) return;
    doAction();
  }, [hasInput, doAction]);

  const handleClear = useCallback(() => {
    setInputValue('');
  }, []);

  // 分享 — 双链接（经典 public + 名片 card）
  const [showShareModal, setShowShareModal] = useState(false);
  const [pubCode, setPubCode] = useState<string | null>(null);
  const [cardCode, setCardCode] = useState<string | null>(null);
  const [shareLoading, setShareLoading] = useState(false);
  const [shareError, setShareError] = useState<string | null>(null);
  const [copied, setCopied] = useState<string | null>(null);

  const handleShare = useCallback(async () => {
    setShareLoading(true);
    setShareError(null);
    try {
      const [pub, card] = await Promise.all([
        getOrCreateShare(skill.id),
        getOrCreateShare(skill.id, 'card'),
      ]);
      setPubCode(pub.shareCode);
      setCardCode(card.shareCode);
      setShowShareModal(true);
    } catch {
      setShareError('分享失败，请先登录');
    } finally {
      setShareLoading(false);
    }
  }, [skill.id]);

  const handleCopy = useCallback((code: string) => {
    const url = `${window.location.origin}/s/${code}`;
    navigator.clipboard.writeText(url).then(() => {
      setCopied(code);
      setTimeout(() => setCopied(null), 2000);
    });
  }, []);

  // ── 自我介绍降级 ──
  const intro = skill.introProfile;
  const introHeadline = intro?.headline || `关于 ${name}`;
  const introBody = intro?.body || skill.openingMessage || `${name}的AI分身`;
  const introClosing = intro?.closing || '';

  // ── Stats ──
  const stats = skill.stats;
  const grainCount = skill.grainCount || 0;
  const sceneTagCount = skill.sceneTags?.length || 0;

  return (
    <div style={{ display: 'flex', justifyContent: 'center', background: '#f1f5f9', minHeight: '100vh' }}>
      {/* ═══ 居中容器 ═══ */}
      <div style={{ width: '100%', maxWidth: 520, minHeight: '100vh', background: '#fff', position: 'relative' }}>

        {/* ═══ Top Bar ═══ */}
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '12px 16px', borderBottom: '1px solid #f1f5f9',
          position: 'sticky', top: 0, background: 'rgba(255,255,255,.95)',
          backdropFilter: 'blur(8px)', zIndex: 10,
        }}>
          <button onClick={() => router.push('/discover')} style={{
            background: 'none', border: 'none', cursor: 'pointer',
            fontSize: 13, color: '#64748b', fontFamily: 'inherit', padding: 0,
          }}>
            ← 返回发现
          </button>

          {!isOrg && (
            <button onClick={handleShare} disabled={shareLoading} style={{
              fontSize: 12, background: '#fff', color: '#334155',
              border: '1px solid #e2e8f0', borderRadius: 8,
              padding: '6px 14px', cursor: 'pointer', fontWeight: 500,
              fontFamily: 'inherit', display: 'flex', alignItems: 'center', gap: 4,
              opacity: shareLoading ? 0.5 : 1,
            }}>
              📤 分享名片
            </button>
          )}
        </div>

        {/* ═══ Hero ═══ */}
        <div style={{ textAlign: 'center', padding: '36px 20px 20px' }}>
          {/* Avatar — 圆形 */}
          <div style={{ position: 'relative', width: 104, height: 104, margin: '0 auto 14px' }}>
            {isOrg ? (
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 0 }}>
                {previewMembers.length > 0 ? previewMembers.map((m, i) => (
                  m.avatarUrl ? (
                    <img key={m.id} src={m.avatarUrl} alt={m.ownerName}
                      style={{
                        width: 56, height: 56, borderRadius: '50%',
                        border: '3px solid #fff', objectFit: 'cover',
                        marginLeft: i > 0 ? -12 : 0, zIndex: 4 - i,
                        boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
                      }} />
                  ) : (
                    <div key={m.id} style={{
                      width: 56, height: 56, borderRadius: '50%',
                      border: '3px solid #fff',
                      background: ORG_AVATAR_COLORS[i % ORG_AVATAR_COLORS.length],
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      color: '#fff', fontSize: 20, fontWeight: 700,
                      marginLeft: i > 0 ? -12 : 0, zIndex: 4 - i,
                    }}>
                      {(m.ownerName || '?')[0]}
                    </div>
                  )
                )) : (
                  <span style={{ fontSize: 48 }}>🏢</span>
                )}
                {orgMembers.length > 4 && (
                  <div style={{
                    width: 56, height: 56, borderRadius: '50%',
                    border: '3px solid #fff', background: 'rgba(255,255,255,0.8)',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    color: '#64748b', fontSize: 14, fontWeight: 600, marginLeft: -12,
                  }}>
                    +{orgMembers.length - 4}
                  </div>
                )}
              </div>
            ) : skill.avatarUrl ? (
              <img src={skill.avatarUrl} alt={name} style={{
                width: 104, height: 104, borderRadius: '50%', objectFit: 'cover',
                border: '3px solid #fff', boxShadow: '0 4px 20px rgba(99,102,241,.15)',
                display: 'block',
              }} />
            ) : (
              <div style={{
                width: 104, height: 104, borderRadius: '50%',
                background: 'linear-gradient(135deg, #6366f1, #a855f7)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                color: '#fff', fontSize: 40, fontWeight: 700,
                boxShadow: '0 4px 20px rgba(99,102,241,.25)',
              }}>
                {name[0]}
              </div>
            )}
            {/* 在线绿点 + 脉冲 */}
            <div style={{
              position: 'absolute', bottom: 8, right: 8,
              width: 14, height: 14, borderRadius: '50%',
              background: '#22c55e', border: '3px solid #fff',
            }} />
            <div style={{
              position: 'absolute', bottom: 8, right: 8,
              width: 14, height: 14, borderRadius: '50%',
              background: '#22c55e',
              animation: 'pulse-ring 2s ease-out infinite',
            }} />
          </div>

          <h1 style={{ fontSize: 24, fontWeight: 700, color: '#0f172a', letterSpacing: '-.5px', margin: 0 }}>
            {name}
          </h1>
          <p style={{ fontSize: 13, color: '#64748b', margin: '4px 0 14px 0' }}>
            {isOrg ? `${skill.memberCount || orgMembers.length} 位成员` : title}
            {skill.domain ? ` · ${skill.domain === 'sales' ? 'B2B企业服务' : skill.domain}` : ''}
          </p>

          {/* Badges */}
          <div style={{ display: 'flex', justifyContent: 'center', gap: 8, flexWrap: 'wrap' }}>
            {grainCount > 0 && (
              <span style={{ fontSize: 11, padding: '5px 12px', borderRadius: 20, fontWeight: 500, background: '#eef2ff', color: '#4f46e5' }}>
                📚 {grainCount} 条实战经验
              </span>
            )}
            {sceneTagCount > 0 && (
              <span style={{ fontSize: 11, padding: '5px 12px', borderRadius: 20, fontWeight: 500, background: '#fef3c7', color: '#92400e' }}>
                🎯 {sceneTagCount} 个业务场景
              </span>
            )}
            {stats && stats.satisfactionRate > 0 && (
              <span style={{ fontSize: 11, padding: '5px 12px', borderRadius: 20, fontWeight: 500, background: '#f0fdf4', color: '#166534' }}>
                👍 {stats.satisfactionRate}% 满意率
              </span>
            )}
          </div>
        </div>

        {/* ═══ Stats Row ═══ */}
        {stats && (stats.conversationCount > 0 || stats.userCount > 0) && (
          <div style={{ display: 'flex', justifyContent: 'center', gap: 28, padding: '0 20px 20px' }}>
            {stats.conversationCount > 0 && (
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: 20, fontWeight: 700, color: '#0f172a', letterSpacing: '-.3px' }}>
                  {stats.conversationCount.toLocaleString()}
                </div>
                <div style={{ fontSize: 11, color: '#94a3b8', marginTop: 3 }}>次对话</div>
              </div>
            )}
            {stats.userCount > 0 && (
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: 20, fontWeight: 700, color: '#0f172a', letterSpacing: '-.3px' }}>
                  {stats.userCount.toLocaleString()}
                </div>
                <div style={{ fontSize: 11, color: '#94a3b8', marginTop: 3 }}>位用户咨询过</div>
              </div>
            )}
            {stats.conversationCount === 0 && stats.userCount === 0 && grainCount > 0 && (
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: 20, fontWeight: 700, color: '#0f172a', letterSpacing: '-.3px' }}>
                  {grainCount}
                </div>
                <div style={{ fontSize: 11, color: '#94a3b8', marginTop: 3 }}>条实战经验</div>
              </div>
            )}
          </div>
        )}

        {/* ═══ 3 段式自我介绍 ═══ */}
        <div style={{
          margin: '0 20px 24px', padding: '20px 18px',
          background: 'linear-gradient(135deg, #fafbff 0%, #f5f3ff 100%)',
          borderRadius: 16, border: '1px solid #e8e4ff',
        }}>
          <div style={{ fontSize: 16, fontWeight: 700, color: '#1e1b4b', lineHeight: 1.4, marginBottom: 8 }}>
            {introHeadline}
          </div>
          <div style={{ width: 32, height: 3, background: 'linear-gradient(90deg, #6366f1, #a855f7)', borderRadius: 2, marginBottom: 10 }} />
          <div style={{ fontSize: 13, color: '#475569', lineHeight: 1.75, marginBottom: 10 }}>
            {introBody}
          </div>
          {introClosing && (
            <div style={{ fontSize: 12, color: '#6366f1', fontWeight: 500, fontStyle: 'italic' }}>
              {introClosing}
            </div>
          )}
        </div>

        {/* ═══ 推荐问题区 ═══ */}
        {(questionsLoading || questions.length > 0) && (
          <>
            <div style={{ margin: '0 20px 8px' }}>
              <div style={{ fontSize: 15, fontWeight: 700, color: '#0f172a' }}>
                关于 {name}{title ? ` · ${title}` : ''}，可以问我
              </div>
              <div style={{ fontSize: 11, color: '#94a3b8', marginTop: 2 }}>
                基于 {grainCount > 0 ? grainCount : '...'} 条实战经验提炼的热门问题
              </div>
            </div>
            <div style={{
              margin: '0 20px 24px',
              display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8,
            }}>
              {questionsLoading ? (
                Array.from({ length: 4 }).map((_, i) => (
                  <div key={i} style={{
                    padding: 14, borderRadius: 12, height: 48,
                    background: '#f1f5f9', animation: 'pulse-ring 1.5s ease-in-out infinite',
                  }} />
                ))
              ) : (
                questions.map((q, i) => (
                  <button key={i} onClick={() => doAction(q)} style={{
                    position: 'relative', overflow: 'hidden',
                    padding: 14, borderRadius: 12,
                    background: '#f1f5f9', color: '#0f172a',
                    cursor: 'pointer', border: '1px solid #e2e8f0',
                    fontSize: 13, lineHeight: 1.5, fontWeight: 500,
                    fontFamily: 'inherit', textAlign: 'left',
                    transition: 'all .2s',
                  }} onMouseEnter={e => {
                    e.currentTarget.style.background = '#e2e8f0';
                    e.currentTarget.style.borderColor = '#cbd5e1';
                    e.currentTarget.style.transform = 'translateY(-2px)';
                    e.currentTarget.style.boxShadow = '0 4px 12px rgba(0,0,0,.06)';
                  }} onMouseLeave={e => {
                    e.currentTarget.style.background = '#f1f5f9';
                    e.currentTarget.style.borderColor = '#e2e8f0';
                    e.currentTarget.style.transform = 'translateY(0)';
                    e.currentTarget.style.boxShadow = 'none';
                  }}>
                    <span style={{
                      position: 'absolute', top: 10, right: 12,
                      fontSize: 28, fontWeight: 800, color: 'rgba(0,0,0,.04)',
                      lineHeight: 1,
                    }}>
                      {(i + 1).toString().padStart(2, '0')}
                    </span>
                    <span style={{ position: 'relative', zIndex: 1, display: 'flex', alignItems: 'flex-start', gap: 8 }}>
                      <span style={{ fontSize: 16, flexShrink: 0, marginTop: 1 }}>💬</span>
                      <span>{q}</span>
                    </span>
                  </button>
                ))
              )}
            </div>
          </>
        )}

        {/* Bottom spacer for fixed input bar */}
        <div style={{ height: 110 }} />
      </div>

      {/* ═══ 底部固定输入栏（fixed to viewport, centered） ═══ */}
      <div style={{
        position: 'fixed', bottom: 0, left: '50%', transform: 'translateX(-50%)',
        width: '100%', maxWidth: 520, background: '#fff',
        borderTop: '1px solid #f1f5f9', padding: '12px 16px 24px', zIndex: 10,
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <div style={{
            flex: 1, display: 'flex', alignItems: 'center', gap: 8,
            background: '#f8fafc', border: '1px solid #e2e8f0',
            borderRadius: 14, padding: '2px 2px 2px 16px',
            transition: 'border-color .2s, box-shadow .2s',
          }} className="input-group">
            <input
              value={inputValue}
              onChange={e => setInputValue(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter' && hasInput) handleSend(); }}
              placeholder={currentPlaceholder}
              style={{
                flex: 1, border: 'none', background: 'transparent', outline: 'none',
                fontSize: 14, padding: '11px 0', color: '#0f172a', minWidth: 0,
                fontFamily: 'inherit',
              }}
            />
            {hasInput && (
              <button onClick={handleClear} style={{
                width: 26, height: 26, borderRadius: '50%', background: '#e2e8f0',
                border: 'none', cursor: 'pointer', fontSize: 12, color: '#94a3b8',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                flexShrink: 0,
              }}>
                ✕
              </button>
            )}
          </div>
          {hasInput ? (
            <button onClick={handleSend} style={{
              padding: '11px 18px', borderRadius: 14, background: '#6366f1',
              color: '#fff', border: 'none', cursor: 'pointer', fontSize: 14,
              fontWeight: 600, whiteSpace: 'nowrap', flexShrink: 0,
              fontFamily: 'inherit',
            }}>
              发送 <span style={{ display: 'inline-block', transition: 'transform .2s' }}>➤</span>
            </button>
          ) : (
            <button onClick={() => doAction()} style={{
              position: 'relative', overflow: 'hidden',
              padding: '11px 18px', borderRadius: 14, background: '#6366f1',
              color: '#fff', border: 'none', cursor: 'pointer', fontSize: 14,
              fontWeight: 600, whiteSpace: 'nowrap', flexShrink: 0,
              fontFamily: 'inherit', transition: 'all .15s',
            }}>
              {/* 脉冲环 */}
              <span style={{
                position: 'absolute', inset: -4, borderRadius: 18,
                border: '2px solid rgba(99,102,241,.2)',
                animation: 'cta-pulse 2s ease-out infinite',
              }} />
              开始咨询 <span style={{ display: 'inline-block', transition: 'transform .2s' }}>→</span>
            </button>
          )}
        </div>
        <div style={{ fontSize: 10, color: '#94a3b8', textAlign: 'center', marginTop: 7 }}>
          {hasInput ? '按 Enter 发送 · 将进入聊天模式' : '输入问题开始对话 · 或点击上方推荐问题'}
        </div>
      </div>

      {/* ═══ 分享弹窗 ═══ */}
      {showShareModal && (pubCode || cardCode) && (
        <div onClick={() => setShowShareModal(false)} style={{
          position: 'fixed', inset: 0, background: 'rgba(0,0,0,.4)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          zIndex: 200, padding: 20,
        }}>
          <div onClick={e => e.stopPropagation()} style={{
            background: '#fff', borderRadius: 20, padding: '28px 24px',
            width: '90%', maxWidth: 380, textAlign: 'center',
            boxShadow: '0 20px 60px rgba(0,0,0,.15)',
          }}>
            <h3 style={{ fontSize: 17, fontWeight: 700, color: '#0f172a', margin: '0 0 4px 0' }}>
              📤 分享{isOrg ? '团队' : ''}名片
            </h3>
            <p style={{ fontSize: 12, color: '#94a3b8', margin: '0 0 16px 0' }}>
              两种分享方式，选一个发给对方
            </p>
            {/* 经典版 */}
            {pubCode && <ShareLinkRow icon="📱" label="经典版" desc="标准落地页，含销冠名片+三模式入口"
              url={`${typeof window !== 'undefined' ? window.location.origin : ''}/s/${pubCode}`}
              copied={copied === pubCode}
              onCopy={() => handleCopy(pubCode!)} />}
            {/* 名片版 */}
            {cardCode && <ShareLinkRow icon="🎴" label="名片版" desc="新卡片式布局，更简洁直观"
              url={`${typeof window !== 'undefined' ? window.location.origin : ''}/s/${cardCode}`}
              copied={copied === cardCode}
              onCopy={() => handleCopy(cardCode!)} />}
            <button onClick={() => setShowShareModal(false)} style={{
              marginTop: 12, padding: '10px 24px', borderRadius: 10,
              background: '#f1f5f9', color: '#64748b', border: 'none',
              cursor: 'pointer', fontSize: 13, fontWeight: 600,
              fontFamily: 'inherit', width: '100%',
            }}>
              关闭
            </button>
          </div>
        </div>
      )}

      {/* ── 分享失败 + 登陆引导 ── */}
      {shareError && (
        <div style={{
          position: 'fixed', bottom: 120, left: '50%', transform: 'translateX(-50%)',
          padding: '10px 20px', borderRadius: 100, background: '#dc2626',
          color: '#fff', fontSize: 13, fontWeight: 500, zIndex: 200,
          boxShadow: '0 4px 12px rgba(220,38,38,.3)',
          display: 'flex', alignItems: 'center', gap: 10,
        }}>
          <span>⚠️ {shareError}</span>
          <button onClick={() => router.push('/login')} style={{
            background: 'rgba(255,255,255,.2)', border: 'none', borderRadius: 6,
            color: '#fff', padding: '3px 10px', cursor: 'pointer',
            fontSize: 12, fontWeight: 600, fontFamily: 'inherit',
          }}>
            去登录
          </button>
          <span onClick={() => setShareError(null)} style={{ cursor: 'pointer', fontSize: 14 }}>✕</span>
        </div>
      )}
    </div>
  );
}
