'use client';

import { useEffect, useState, useRef, useCallback, useId } from 'react';
import { useRouter, useParams } from 'next/navigation';
import PlatformTopBar from '@/components/ui/PlatformTopBar';
import { getToken } from '@/lib/storage';
import { updateGrain, deprecateGrain, restoreGrain } from '@/lib/api/grain';

// ── Types ──

interface GrainItem {
  id: string;
  sceneTag: string;
  sceneDescription: string;
  expertThought: string;
  standardScript: string;
  commonMistakes: string;
  status: string;
  qualityScore?: number | null;
  verificationNotes?: string | null;
  sourceType?: string;
  sourceMaterialId?: string;
  sourceMaterialName?: string;
}

function isBypassed(g: GrainItem): boolean {
  return g.qualityScore != null && g.qualityScore <= 0 && g.status === 'active';
}

// ── Design tokens ──

const C = {
  // brand
  blue: '#2147ff',
  blueHover: '#1a3ad6',
  blueBg: '#eef2ff',

  // semantic
  green: '#059669',
  greenBg: '#ecfdf5',
  greenBorder: '#a7f3d0',
  orange: '#b45309',
  orangeBg: '#fffbeb',
  orangeBorder: '#fde68a',
  red: '#dc2626',
  redBg: '#fef2f2',
  redBorder: '#fecaca',

  // text
  text: '#10162f',
  textMid: '#5b6886',
  textLow: '#8e97b0',

  // surface
  pageBg: '#f8faff',
  cardBg: '#ffffff',
  cardBorder: '#e8ecf4',
  cardHover: '#f5f7fd',

  // shadow
  shadowSm: '0 1px 3px rgba(16,22,47,0.06)',
  shadowMd: '0 4px 16px rgba(16,22,47,0.08)',
  shadowLg: '0 12px 40px rgba(16,22,47,0.12)',
};

// ── Typography scale ──

const T = {
  h1: { fontSize: 28, fontWeight: 800, letterSpacing: '-0.03em', lineHeight: 1.2 } as const,
  h2: { fontSize: 18, fontWeight: 700, letterSpacing: '-0.01em' } as const,
  body: { fontSize: 14, lineHeight: 1.6 } as const,
  bodySm: { fontSize: 13, lineHeight: 1.55 } as const,
  caption: { fontSize: 12, lineHeight: 1.5 } as const,
  tiny: { fontSize: 11, lineHeight: 1.4 } as const,
  micro: { fontSize: 10, fontWeight: 700, letterSpacing: '0.04em', textTransform: 'uppercase' } as const,
};

// ── Helpers ──

function qualityBadge(score: number | null | undefined) {
  if (score == null) return null;
  const color = score >= 4 ? C.green : score >= 3 ? C.orange : score <= 0 ? C.orange : C.red;
  const bg = score >= 4 ? C.greenBg : score >= 3 ? C.orangeBg : score <= 0 ? C.orangeBg : C.redBg;
  const label = score <= 0 ? '待确认' : score.toFixed(1);
  return { color, bg, label };
}

function trunc(s: string, n: number) { return s && s.length > n ? s.substring(0, n) + '...' : s || ''; }

function groupByScene(grains: GrainItem[]): Map<string, GrainItem[]> {
  const map = new Map<string, GrainItem[]>();
  for (const g of grains) {
    const key = g.sceneTag || '通用';
    if (!map.has(key)) map.set(key, []);
    map.get(key)!.push(g);
  }
  return map;
}

function avgScore(grains: GrainItem[]): number | null {
  const scores = grains.filter(g => g.qualityScore != null && g.qualityScore > 0).map(g => g.qualityScore!);
  if (scores.length === 0) return null;
  return scores.reduce((a, b) => a + b, 0) / scores.length;
}

// ── Style helpers ──

const btnReset: React.CSSProperties = { background: 'none', border: 'none', cursor: 'pointer', fontFamily: 'inherit', padding: 0 };

const transition = 'all 0.2s ease';

// ═══════════════════════════════════════════════════
// Main component
// ═══════════════════════════════════════════════════

export default function AuditPage() {
  const { skillId } = useParams<{ skillId: string }>();
  const router = useRouter();

  const [grains, setGrains] = useState<GrainItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [publishing, setPublishing] = useState(false);
  const publishLock = useRef(false);
  const [filterTag, setFilterTag] = useState<string | null>(null);
  const [expandedTags, setExpandedTags] = useState<Set<string>>(new Set());

  const [editingGrain, setEditingGrain] = useState<GrainItem | null>(null);
  const [editForm, setEditForm] = useState({ sceneTag: '', sceneDescription: '', expertThought: '', standardScript: '', commonMistakes: '' });
  const [saving, setSaving] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);

  const authHeaders = useCallback((): Record<string, string> => {
    const token = getToken();
    return token ? { Authorization: `Bearer ${token}` } : {};
  }, []);

  /* ── Fetch ── */

  const fetchGrains = useCallback(async (signal?: AbortSignal) => {
    setLoading(true);
    try {
      const r = await fetch(`/api/v1/skills/${skillId}/grains`, { headers: authHeaders(), signal });
      const d = await r.json();
      if (d.code === 200) {
        setGrains(d.data || []);
      } else if (d.code === 403) {
        setError('无权访问此分身');
      } else {
        throw new Error(d.message || '加载失败');
      }
    } catch (e: any) {
      if (e.name !== 'AbortError') setError(e.message || '加载失败');
    } finally {
      setLoading(false);
    }
  }, [skillId, authHeaders]);

  useEffect(() => {
    const ac = new AbortController();
    fetchGrains(ac.signal);
    return () => ac.abort();
  }, [fetchGrains]);

  /* ── Publish ── */

  const handlePublish = async () => {
    if (publishLock.current) return;
    publishLock.current = true;
    setPublishing(true);
    try {
      const r = await fetch(`/api/v1/skills/${skillId}/status`, {
        method: 'PUT',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ status: 'published' }),
      });
      const d = await r.json();
      if (d.code === 200) {
        router.push('/platform/my');
      } else {
        setError(d.message || '发布失败');
      }
    } catch {
      setError('网络错误');
    } finally {
      setPublishing(false);
      publishLock.current = false;
    }
  };

  /* ── Edit ── */

  const openEdit = (g: GrainItem) => {
    setEditingGrain(g);
    setEditForm({
      sceneTag: g.sceneTag || '',
      sceneDescription: g.sceneDescription || '',
      expertThought: g.expertThought || '',
      standardScript: g.standardScript || '',
      commonMistakes: g.commonMistakes || '',
    });
    setEditError(null);
  };

  const handleSaveEdit = async () => {
    if (!editingGrain) return;
    setSaving(true);
    setEditError(null);
    try {
      const result = await updateGrain(editingGrain.id, editForm);
      setGrains(prev => prev.map(g => g.id === editingGrain.id ? {
        ...g,
        sceneTag: result.sceneTag,
        sceneDescription: editForm.sceneDescription,
        expertThought: result.expertThought,
        standardScript: result.standardScript,
        commonMistakes: editForm.commonMistakes,
        status: result.status,
      } : g));
      setEditingGrain(null);
    } catch (e: any) {
      setEditError(e.message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  /* ── Deprecate / Restore ── */

  const handleDeprecate = async (grainId: string) => {
    if (!confirm('确定废弃这条经验吗？废弃后不会在对话中被检索。')) return;
    try {
      await deprecateGrain(grainId);
      setGrains(prev => prev.map(g => g.id === grainId ? { ...g, status: 'deprecated' } : g));
    } catch (e: any) {
      alert(e.message || '操作失败');
    }
  };

  const handleRestore = async (grainId: string) => {
    try {
      await restoreGrain(grainId);
      setGrains(prev => prev.map(g => g.id === grainId ? { ...g, status: 'active' } : g));
    } catch (e: any) {
      alert(e.message || '操作失败');
    }
  };

  /* ── Quick actions ── */

  const handleQuickKeep = async (g: GrainItem) => {
    try {
      const result = await updateGrain(g.id, {
        sceneTag: g.sceneTag || '',
        expertThought: g.expertThought || '',
        standardScript: g.standardScript || '',
        commonMistakes: g.commonMistakes || '',
      });
      setGrains(prev => prev.map(x => x.id === g.id ? {
        ...x, sceneTag: result.sceneTag, expertThought: result.expertThought,
        standardScript: result.standardScript, qualityScore: 3.0, verificationNotes: null,
      } : x));
    } catch (e: any) { alert(e.message || '操作失败'); }
  };

  const handleQuickDelete = async (g: GrainItem) => {
    if (!confirm('确定删除这条经验？')) return;
    try {
      await deprecateGrain(g.id);
      setGrains(prev => prev.map(x => x.id === g.id ? { ...x, status: 'deprecated' } : x));
    } catch (e: any) { alert(e.message || '操作失败'); }
  };

  /* ── Derived ── */

  const activeGrains = grains.filter(g => g.status !== 'deprecated');
  const bypassed = activeGrains.filter(isBypassed);
  const confirmed = activeGrains.filter(g => !isBypassed(g));
  const filteredConfirmed = filterTag ? confirmed.filter(g => (g.sceneTag || '通用') === filterTag) : confirmed;
  const grouped = groupByScene(filteredConfirmed);
  const allTags = Array.from(groupByScene(confirmed).keys());
  const allSceneTags = Array.from(new Set([...allTags, ...bypassed.map(g => g.sceneTag || '通用')]));

  /* ── Loading ── */

  if (loading) {
    return (
      <div style={{ minHeight: '100vh', background: C.pageBg, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <div style={{ textAlign: 'center' }}>
          <div style={{
            width: 48, height: 48, borderRadius: 14, margin: '0 auto 20px',
            background: `linear-gradient(135deg, ${C.blue}, #6366f1)`,
            animation: 'auditPulse 2s ease-in-out infinite',
          }} />
          <p style={{ ...T.body, color: C.textLow }}>AI 正在分析你的经验...</p>
        </div>
      </div>
    );
  }

  /* ── Render ── */

  const fileName = grains[0]?.sourceMaterialName || '访谈记录';

  return (
    <div style={{ minHeight: '100vh', background: C.pageBg, paddingBottom: 100 }}>
      <PlatformTopBar backTo="/platform/my" backLabel="我的分身" />

      {/* ═══ Hero ═══ */}
      <div style={{
        background: '#ffffff', borderBottom: `1px solid ${C.cardBorder}`,
        padding: '36px 20px 32px',
      }}>
        <div style={{ maxWidth: 800, margin: '0 auto' }}>

          <h1 style={{ ...T.h1, color: C.text, margin: '0 0 8px' }}>
            经验萃取完成
          </h1>
          <p style={{ ...T.body, color: C.textMid, margin: '0 0 24px' }}>
            从 <b style={{ color: C.text, fontWeight: 600 }}>「{fileName}」</b> 中提炼了{' '}
            <b style={{ color: C.blue, fontWeight: 700 }}>{grains.length} 条</b> 销售经验
          </p>

          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
            <StatPill icon="✓" label="自动通过" value={confirmed.length} color={C.green} bg={C.greenBg} />
            <StatPill icon="◈" label="覆盖场景" value={allSceneTags.length} color={C.blue} bg={C.blueBg} />
            {bypassed.length > 0 && (
              <StatPill icon="!" label="需人工确认" value={bypassed.length} color={C.orange} bg={C.orangeBg} />
            )}
          </div>
        </div>
      </div>

      <div style={{ maxWidth: 800, margin: '0 auto', padding: '0 20px' }}>
        {/* ═══ Error ═══ */}
        {error && (
          <div style={{
            marginTop: 20, padding: '12px 16px', borderRadius: 14, ...T.caption,
            background: C.redBg, border: `1px solid ${C.redBorder}`, color: C.red,
            display: 'flex', justifyContent: 'space-between', alignItems: 'center',
          }}>
            <span>{error}</span>
            <button onClick={() => { setError(null); fetchGrains(); }}
              style={{ ...btnReset, color: C.red, fontWeight: 600, fontSize: 12 }}>
              重试
            </button>
          </div>
        )}

        {/* ═══ Empty ═══ */}
        {grains.length === 0 && !error && (
          <div style={{ textAlign: 'center', padding: '80px 0' }}>
            <div style={{ fontSize: 56, marginBottom: 16, opacity: 0.6 }}>🧠</div>
            <p style={{ ...T.body, color: C.textLow, marginBottom: 8 }}>AI 还在分析你的访谈内容</p>
            <p style={{ ...T.caption, color: C.textLow }}>通常需要 2-3 分钟，请稍等片刻</p>
          </div>
        )}

        {/* ═══ Pending review ═══ */}
        {bypassed.length > 0 && (
          <div style={{ marginTop: 24 }}>
            <div style={{
              padding: '12px 18px', borderRadius: '14px 14px 0 0',
              background: C.orangeBg, border: `1px solid ${C.orangeBorder}`, borderBottom: 'none',
              ...T.bodySm, fontWeight: 600, color: C.orange,
            }}>
              ⚠️ AI 对这 {bypassed.length} 条经验把握不够，请你花 1 分钟确认
            </div>

            <div style={{
              background: C.cardBg, border: `1px solid ${C.orangeBorder}`, borderTop: 'none',
              borderRadius: '0 0 14px 14px', overflow: 'hidden',
            }}>
              {bypassed.map((g, i) => (
                <div key={g.id} style={{
                  padding: '24px 20px',
                  borderTop: i > 0 ? `1px solid ${C.cardBorder}` : 'none',
                }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 14 }}>
                    <span style={{
                      padding: '3px 12px', borderRadius: 100, ...T.tiny, fontWeight: 600,
                      background: C.blueBg, color: C.blue,
                    }}>
                      {g.sceneTag || '通用'}
                    </span>
                    <span style={{
                      padding: '2px 10px', borderRadius: 100, ...T.micro, fontWeight: 600,
                      background: C.orangeBg, color: C.orange, textTransform: 'none', letterSpacing: 0,
                    }}>
                      ⚠ 待确认
                    </span>
                  </div>

                  <SkillVsMistakeCard grain={g} />

                  <div style={{ display: 'flex', gap: 10 }}>
                    <button onClick={() => handleQuickKeep(g)} style={{
                      flex: 1, padding: '11px 0', borderRadius: 100, border: 'none', cursor: 'pointer',
                      background: C.blue, color: '#fff', ...T.bodySm, fontWeight: 600, fontFamily: 'inherit',
                      transition,
                    }}
                      onMouseEnter={e => { e.currentTarget.style.background = C.blueHover; }}
                      onMouseLeave={e => { e.currentTarget.style.background = C.blue; }}
                    >
                      确认，保留
                    </button>
                    <button onClick={() => openEdit(g)} style={{
                      padding: '11px 20px', borderRadius: 100, border: `1.5px solid ${C.cardBorder}`, cursor: 'pointer',
                      background: '#fff', color: C.textMid, ...T.bodySm, fontWeight: 500, fontFamily: 'inherit', transition,
                    }}
                      onMouseEnter={e => { e.currentTarget.style.background = C.cardHover; }}
                      onMouseLeave={e => { e.currentTarget.style.background = '#fff'; }}
                    >
                      修改
                    </button>
                    <button onClick={() => handleQuickDelete(g)} style={{
                      padding: '11px 16px', borderRadius: 100, border: 'none', cursor: 'pointer',
                      background: 'none', color: C.textLow, ...T.bodySm, fontFamily: 'inherit', transition,
                    }}
                      onMouseEnter={e => { e.currentTarget.style.color = C.red; }}
                      onMouseLeave={e => { e.currentTarget.style.color = C.textLow; }}
                    >
                      删除
                    </button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* ═══ Scene filter ═══ */}
        {allSceneTags.length > 1 && (
          <div style={{
            marginTop: 24, display: 'flex', gap: 8, overflowX: 'auto', paddingBottom: 4,
            WebkitOverflowScrolling: 'touch', scrollbarWidth: 'none',
          }}>
            <style>{`.chip-scroll::-webkit-scrollbar { display: none }`}</style>
            <button onClick={() => setFilterTag(null)} style={{
              padding: '7px 18px', borderRadius: 100, border: 'none', cursor: 'pointer',
              ...T.caption, fontWeight: 600, fontFamily: 'inherit', whiteSpace: 'nowrap', transition,
              background: filterTag === null ? C.blue : '#fff',
              color: filterTag === null ? '#fff' : C.textMid,
              boxShadow: filterTag === null ? C.shadowSm : `0 0 0 1px ${C.cardBorder}`,
            }}>
              全部场景 ({confirmed.length})
            </button>
            {allSceneTags.map(tag => {
              const count = confirmed.filter(g => (g.sceneTag || '通用') === tag).length;
              const active = filterTag === tag;
              return (
                <button key={tag} onClick={() => setFilterTag(active ? null : tag)} style={{
                  padding: '7px 18px', borderRadius: 100, border: 'none', cursor: 'pointer',
                  ...T.caption, fontWeight: 600, fontFamily: 'inherit', whiteSpace: 'nowrap', transition,
                  background: active ? C.blue : '#fff',
                  color: active ? '#fff' : C.textMid,
                  boxShadow: active ? C.shadowSm : `0 0 0 1px ${C.cardBorder}`,
                }}>
                  {tag} ({count})
                </button>
              );
            })}
          </div>
        )}

        {/* ═══ Confirmed ═══ */}
        {Array.from(grouped.entries()).map(([tag, sceneGrains]) => {
          const expanded = expandedTags.has(tag);
          return (
            <SceneCard
              key={tag}
              tag={tag}
              count={sceneGrains.length}
              avgScore={avgScore(sceneGrains)}
              first={sceneGrains[0]}
              rest={sceneGrains.slice(1)}
              expanded={expanded}
              onToggle={() => {
                const next = new Set(expandedTags);
                if (expanded) next.delete(tag); else next.add(tag);
                setExpandedTags(next);
              }}
              onEdit={openEdit}
              onDeprecate={handleDeprecate}
            />
          );
        })}

        {/* ═══ Deprecated ═══ */}
        {grains.some(g => g.status === 'deprecated') && (
          <div style={{ marginTop: 40 }}>
            <p style={{ ...T.caption, fontWeight: 600, color: C.textLow, marginBottom: 10 }}>
              已废弃 · {grains.filter(g => g.status === 'deprecated').length} 条
            </p>
            {grains.filter(g => g.status === 'deprecated').map(g => (
              <div key={g.id} style={{
                padding: '10px 16px', borderRadius: 12, marginBottom: 6,
                background: '#f9fafb', border: `1px solid ${C.cardBorder}`,
                display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                opacity: 0.65,
              }}>
                <span style={{ ...T.bodySm, color: C.textLow }}>
                  <span style={{ fontWeight: 600, color: C.textMid }}>{g.sceneTag || '通用'}</span>
                  <span style={{ margin: '0 8px', color: C.cardBorder }}>·</span>
                  <span>{trunc(g.standardScript, 40)}</span>
                </span>
                <button onClick={() => handleRestore(g.id)} style={{
                  padding: '4px 14px', borderRadius: 100, border: 'none', cursor: 'pointer', ...T.tiny, fontWeight: 600,
                  background: C.greenBg, color: C.green, fontFamily: 'inherit', transition,
                }}
                  onMouseEnter={e => { e.currentTarget.style.background = C.greenBorder; }}
                  onMouseLeave={e => { e.currentTarget.style.background = C.greenBg; }}
                >
                  恢复
                </button>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* ═══ Bottom bar ═══ */}
      {grains.length > 0 && (
        <div style={{
          position: 'fixed', bottom: 0, left: 0, right: 0, zIndex: 50,
          padding: '16px 20px', background: 'rgba(255,255,255,0.88)',
          backdropFilter: 'blur(16px)', WebkitBackdropFilter: 'blur(16px)',
          borderTop: `1px solid ${C.cardBorder}`,
          display: 'flex', gap: 12, justifyContent: 'center',
        }}>
          <button onClick={handlePublish} disabled={publishing} style={{
            padding: '14px 48px', borderRadius: 100, border: 'none', cursor: publishing ? 'not-allowed' : 'pointer',
            background: publishing ? '#a0b4ff' : C.blue, color: '#fff', ...T.bodySm, fontWeight: 700,
            fontFamily: 'inherit', transition, opacity: publishing ? 0.7 : 1,
            boxShadow: publishing ? 'none' : '0 4px 20px rgba(33,71,255,0.25)',
          }}
            onMouseEnter={e => { if (!publishing) e.currentTarget.style.boxShadow = '0 6px 28px rgba(33,71,255,0.35)'; }}
            onMouseLeave={e => { if (!publishing) e.currentTarget.style.boxShadow = '0 4px 20px rgba(33,71,255,0.25)'; }}
          >
            {publishing ? '发布中...' : `发布分身 (${activeGrains.length} 条经验)`}
          </button>
          <button onClick={() => router.back()} style={{
            padding: '14px 24px', borderRadius: 100, border: `1.5px solid ${C.cardBorder}`, cursor: 'pointer',
            background: '#fff', color: C.textMid, ...T.bodySm, fontWeight: 500, fontFamily: 'inherit', transition,
          }}
            onMouseEnter={e => { e.currentTarget.style.background = C.cardHover; }}
            onMouseLeave={e => { e.currentTarget.style.background = '#fff'; }}
          >
            返回
          </button>
        </div>
      )}

      {/* ═══ Edit Modal ═══ */}
      {editingGrain && (
        <div
          onClick={() => setEditingGrain(null)}
          onKeyDown={e => { if (e.key === 'Escape') setEditingGrain(null); }}
          tabIndex={-1}
          style={{ position: 'fixed', inset: 0, background: 'rgba(16,22,47,0.3)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200, padding: 20, backdropFilter: 'blur(4px)' }}
        >
          <div onClick={e => e.stopPropagation()} style={{
            background: '#fff', borderRadius: 24, padding: '28px 28px 24px', width: '90%', maxWidth: 500,
            boxShadow: C.shadowLg, maxHeight: '90vh', overflowY: 'auto',
          }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 20 }}>
              <h3 style={{ ...T.h2, color: C.text, margin: 0 }}>编辑经验</h3>
              <button onClick={() => setEditingGrain(null)} style={{
                ...btnReset, width: 32, height: 32, borderRadius: 8, display: 'flex', alignItems: 'center',
                justifyContent: 'center', color: C.textLow, fontSize: 18, transition,
              }}
                onMouseEnter={e => { e.currentTarget.style.background = C.cardHover; e.currentTarget.style.color = C.text; }}
                onMouseLeave={e => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = C.textLow; }}
              >
                ×
              </button>
            </div>

            {editError && (
              <div style={{ marginBottom: 14, padding: '10px 14px', borderRadius: 12, ...T.caption, background: C.redBg, border: `1px solid ${C.redBorder}`, color: C.red }}>
                {editError}
              </div>
            )}

            <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
              <Field label="场景标签" value={editForm.sceneTag} onChange={v => setEditForm(f => ({ ...f, sceneTag: v }))} />
              <Field label="场景描述" value={editForm.sceneDescription} onChange={v => setEditForm(f => ({ ...f, sceneDescription: v }))} textarea rows={2} />
              <Field label="思路" value={editForm.expertThought} onChange={v => setEditForm(f => ({ ...f, expertThought: v }))} textarea rows={3} />
              <Field label="话术" value={editForm.standardScript} onChange={v => setEditForm(f => ({ ...f, standardScript: v }))} textarea rows={3} />
              <Field label="常见错误" value={editForm.commonMistakes} onChange={v => setEditForm(f => ({ ...f, commonMistakes: v }))} textarea rows={2} />
            </div>

            <div style={{ display: 'flex', gap: 10, marginTop: 22 }}>
              <button onClick={handleSaveEdit} disabled={saving} style={{
                flex: 1, padding: '12px 0', borderRadius: 100, border: 'none', cursor: saving ? 'not-allowed' : 'pointer',
                background: C.blue, color: '#fff', ...T.bodySm, fontWeight: 600, fontFamily: 'inherit', opacity: saving ? 0.6 : 1, transition,
              }}>
                {saving ? '保存中...' : '保存'}
              </button>
              <button onClick={() => setEditingGrain(null)} style={{
                padding: '12px 24px', borderRadius: 100, border: `1.5px solid ${C.cardBorder}`, cursor: 'pointer',
                background: '#fff', color: C.textMid, ...T.bodySm, fontWeight: 500, fontFamily: 'inherit', transition,
              }}>
                取消
              </button>
            </div>
          </div>
        </div>
      )}

      <style>{`
        @keyframes auditPulse {
          0%, 100% { opacity: 1; transform: scale(1); }
          50% { opacity: 0.6; transform: scale(0.94); }
        }
      `}</style>
    </div>
  );
}

// ═══════════════════════════════════════════════════
// Sub-components
// ═══════════════════════════════════════════════════

function StatPill({ icon, label, value, color, bg }: { icon: string; label: string; value: number; color: string; bg: string }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 6,
      padding: '7px 16px', borderRadius: 100, background: bg,
      fontSize: 13, fontWeight: 500,
    }}>
      <span style={{ fontWeight: 700, color, fontSize: 11 }}>{icon}</span>
      <span style={{ fontWeight: 700, color }}>{value}</span>
      <span style={{ color, opacity: 0.75 }}>{label}</span>
    </div>
  );
}

function Field({ label, value, onChange, textarea, rows }: {
  label: string; value: string; onChange: (v: string) => void; textarea?: boolean; rows?: number;
}) {
  const id = useId();
  const [focused, setFocused] = useState(false);
  const common = {
    id,
    value,
    onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => onChange(e.target.value),
    onFocus: () => setFocused(true),
    onBlur: () => setFocused(false),
    style: {
      width: '100%', padding: '10px 14px', borderRadius: 12,
      border: `1.5px solid ${focused ? C.blue : C.cardBorder}`, background: '#f8faff',
      fontSize: 13, outline: 'none', fontFamily: 'inherit',
      color: C.text, boxSizing: 'border-box' as const,
      resize: 'none' as const, transition,
    },
  };
  return (
    <div>
      <label htmlFor={id} style={{ display: 'block', ...T.tiny, fontWeight: 600, color: C.textMid, marginBottom: 5, textTransform: 'none', letterSpacing: 0 }}>
        {label}
      </label>
      {textarea ? <textarea {...common} rows={rows || 3} /> : <input {...common} />}
    </div>
  );
}

function SceneCard({ tag, count, avgScore, first, rest, expanded, onToggle, onEdit, onDeprecate }: {
  tag: string; count: number; avgScore: number | null;
  first: GrainItem; rest: GrainItem[];
  expanded: boolean; onToggle: () => void;
  onEdit: (g: GrainItem) => void; onDeprecate: (id: string) => void;
}) {
  const badge = qualityBadge(avgScore);
  return (
    <div style={{
      marginTop: 16, background: C.cardBg, borderRadius: 18,
      border: `1px solid ${C.cardBorder}`, boxShadow: C.shadowSm,
      overflow: 'hidden', transition,
    }}>
      <button onClick={onToggle} style={{
        width: '100%', padding: '16px 20px', border: 'none', cursor: 'pointer', ...btnReset,
        display: 'flex', alignItems: 'center', justifyContent: 'space-between', transition,
      }}
        onMouseEnter={e => { e.currentTarget.style.background = C.cardHover; }}
        onMouseLeave={e => { e.currentTarget.style.background = 'transparent'; }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <span style={{
            padding: '4px 14px', borderRadius: 100, ...T.tiny, fontWeight: 700,
            background: C.blueBg, color: C.blue,
          }}>
            {tag}
          </span>
          {badge && (
            <span style={{
              padding: '2px 10px', borderRadius: 100, ...T.micro, fontWeight: 700, textTransform: 'none', letterSpacing: 0,
              background: badge.bg, color: badge.color,
            }}>
              ⭐ {badge.label}
            </span>
          )}
          <span style={{ ...T.caption, color: C.textLow }}>{count} 条</span>
        </div>
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" style={{ transition, transform: expanded ? 'rotate(180deg)' : 'rotate(0)' }}>
          <path d="M4 6L8 10L12 6" stroke={C.textLow} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </button>

      <div style={{ padding: '0 20px 16px' }}>
        <GrainRow grain={first} onEdit={onEdit} onDeprecate={onDeprecate} />
      </div>

      {expanded && rest.length > 0 && (
        <div style={{ padding: '0 20px 16px', borderTop: `1px solid #f0f2f7` }}>
          {rest.map(g => (
            <div key={g.id} style={{ paddingTop: 12 }}>
              <GrainRow grain={g} onEdit={onEdit} onDeprecate={onDeprecate} compact />
            </div>
          ))}
        </div>
      )}

      {rest.length > 0 && !expanded && (
        <div style={{ padding: '0 20px 16px' }}>
          <button onClick={onToggle} style={{ ...btnReset, ...T.caption, color: C.blue, fontWeight: 500, transition }}>
            展开同场景另外 {rest.length} 条 →
          </button>
        </div>
      )}
    </div>
  );
}

function GrainRow({ grain: g, onEdit, onDeprecate, compact }: {
  grain: GrainItem; onEdit: (g: GrainItem) => void; onDeprecate: (id: string) => void; compact?: boolean;
}) {
  const badge = qualityBadge(g.qualityScore);
  const bypassed = isBypassed(g);
  return (
    <div style={{
      padding: compact ? '10px 0' : '0',
      borderBottom: compact ? `1px solid #f0f2f7` : 'none',
    }}>
      {!compact && (
        <div className="vs-cards" style={{ marginBottom: 12 }}>
          <SkillVsMistakeCard grain={g} />
        </div>
      )}
      {compact && (
        <div style={{ display: 'flex', gap: 12 }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <p style={{
              ...T.bodySm, color: C.text, margin: '0 0 4px', lineHeight: 1.5,
              borderLeft: `2px solid ${C.blue}`, paddingLeft: 10, fontStyle: 'italic',
            }}>
              "{trunc(g.standardScript, 80)}"
            </p>
            <p style={{ ...T.tiny, color: C.textMid, margin: 0 }}>{trunc(g.expertThought, 60)}</p>
          </div>
        </div>
      )}

      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        marginTop: compact ? 6 : 0, paddingTop: compact ? 6 : 10,
        borderTop: compact ? 'none' : `1px solid #f0f2f7`,
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          {badge && !bypassed && (
            <span style={{ padding: '2px 8px', borderRadius: 100, ...T.micro, fontWeight: 700, textTransform: 'none', letterSpacing: 0, background: badge.bg, color: badge.color }}>
              ⭐ {badge.label}
            </span>
          )}
          {bypassed && (
            <span style={{ padding: '2px 8px', borderRadius: 100, ...T.micro, fontWeight: 700, textTransform: 'none', letterSpacing: 0, background: C.orangeBg, color: C.orange }}>
              ⚠ 待确认
            </span>
          )}
          {g.sourceMaterialName && (
            <span style={{ ...T.tiny, color: C.textLow }} title={g.sourceMaterialName}>
              {trunc(g.sourceMaterialName, 24)}
            </span>
          )}
        </div>
        <div style={{ display: 'flex', gap: 4 }}>
          <button onClick={() => onEdit(g)} style={{
            ...btnReset, padding: '4px 10px', borderRadius: 8, color: C.blue, ...T.tiny, fontWeight: 500, transition,
          }}
            onMouseEnter={e => { e.currentTarget.style.background = C.blueBg; }}
            onMouseLeave={e => { e.currentTarget.style.background = 'transparent'; }}
          >
            编辑
          </button>
          <button onClick={() => onDeprecate(g.id)} style={{
            ...btnReset, padding: '4px 10px', borderRadius: 8, color: C.textLow, ...T.tiny, fontWeight: 500, transition,
          }}
            onMouseEnter={e => { e.currentTarget.style.background = C.redBg; e.currentTarget.style.color = C.red; }}
            onMouseLeave={e => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = C.textLow; }}
          >
            废弃
          </button>
        </div>
      </div>
    </div>
  );
}

function SkillVsMistakeCard({ grain }: { grain: GrainItem }) {
  return (
    <div className="vs-cards" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
      <div style={{ padding: '16px', borderRadius: 14, background: C.greenBg, border: `1px solid ${C.greenBorder}` }}>
        <p style={{ ...T.micro, color: C.green, margin: '0 0 8px' }}>✓ 怎么做</p>
        <p style={{
          ...T.bodySm, color: C.text, margin: '0 0 10px', lineHeight: 1.6,
          borderLeft: `3px solid ${C.blue}`, paddingLeft: 12, fontStyle: 'italic',
        }}>
          "{trunc(grain.standardScript, 120)}"
        </p>
        <p style={{ ...T.caption, color: C.textMid, margin: 0, lineHeight: 1.5 }}>
          💡 {trunc(grain.expertThought, 80)}
        </p>
      </div>

      <div style={{ padding: '16px', borderRadius: 14, background: C.redBg, border: `1px solid ${C.redBorder}` }}>
        <p style={{ ...T.micro, color: C.red, margin: '0 0 8px' }}>✕ 不要怎么做</p>
        <p style={{ ...T.bodySm, color: C.text, margin: 0, lineHeight: 1.6 }}>
          {grain.commonMistakes || '未记录常见错误'}
        </p>
      </div>

      <style>{`
        @media (max-width: 500px) {
          .vs-cards { grid-template-columns: 1fr !important; }
        }
      `}</style>
    </div>
  );
}
