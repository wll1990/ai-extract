'use client';

import React, { useEffect, useRef, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import {
  getTrialGrain, getTrialSession, getTrialSkill, listTrialSessions, trialChat,
  type TrialGrain, type TrialSessionItem, type TrialSkillDetail, type TrialTurnResult,
} from '@/lib/api/trial';
import { RefineDrawer } from '@/components/trial/RefineDrawer';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { getUser } from '@/lib/storage';
import { Permission } from '@/lib/permissions';

interface Turn {
  id: string;
  question: string;
  answer: string;
  grains: TrialGrain[];
  avgSimilarity?: string;
  sourceNames?: string;
  canApplyDirectly: boolean;
  published: boolean;
  streaming: boolean;
}

/**
 * 试问台 —— 属主/管理员先自己问几句，看分身引用了哪几条经验、答得准不准。
 *
 * 左侧问答，右侧引用经验；点某条经验打开纠偏抽屉。
 * 试问数据不进任何业务表（后端 persistMode=TRIAL）。
 */
export default function TrialPage() {
  const params = useParams();
  const router = useRouter();
  const skillId = params.skillId as string;

  const [skill, setSkill] = useState<TrialSkillDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [sessionId, setSessionId] = useState<string | undefined>();
  const [turns, setTurns] = useState<Turn[]>([]);
  const [input, setInput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const [activeGrain, setActiveGrain] = useState<TrialGrain | null>(null);

  const [historyOpen, setHistoryOpen] = useState(false);
  const [sessions, setSessions] = useState<TrialSessionItem[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);

  const abortRef = useRef<AbortController | null>(null);
  const bottomRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    getTrialSkill(skillId)
      .then(setSkill)
      .catch(e => setError(e instanceof Error ? e.message : '分身信息加载失败'))
      .finally(() => setLoading(false));
    return () => { abortRef.current?.abort(); };
  }, [skillId]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [turns]);

  const updateTurn = (id: string, patch: (t: Turn) => Turn) => {
    setTurns(prev => prev.map(t => (t.id === id ? patch(t) : t)));
  };

  const send = (text: string) => {
    const msg = text.trim();
    if (!msg || streaming) return;
    const turnId = crypto.randomUUID();
    setTurns(prev => [...prev, {
      id: turnId, question: msg, answer: '', grains: [],
      canApplyDirectly: true, published: skill?.status === 'published', streaming: true,
    }]);
    setInput('');
    setStreaming(true);
    setError('');
    abortRef.current = trialChat(skillId, { message: msg, sessionId }, {
      onChunk: chunk => updateTurn(turnId, t => ({ ...t, answer: t.answer + chunk })),
      onEvent: (type, data) => {
        if (type !== 'trial') return;
        const d = data as unknown as TrialTurnResult;
        setSessionId(d.sessionId);
        updateTurn(turnId, t => ({
          ...t,
          grains: d.grains ?? [],
          avgSimilarity: d.avgSimilarity,
          sourceNames: d.sourceNames,
          canApplyDirectly: d.canApplyDirectly,
          published: d.published,
        }));
      },
      onDone: () => {
        updateTurn(turnId, t => ({ ...t, streaming: false }));
        setStreaming(false);
      },
      onError: message => {
        updateTurn(turnId, t => ({ ...t, streaming: false }));
        setStreaming(false);
        setError(message);
      },
    });
  };

  const startNew = () => {
    abortRef.current?.abort();
    setTurns([]);
    setSessionId(undefined);
    setStreaming(false);
    setActiveGrain(null);
  };

  const openHistory = async () => {
    setHistoryOpen(true);
    setHistoryLoading(true);
    try {
      const d = await listTrialSessions(skillId, 1, 20);
      setSessions(d.content);
    } catch (e) {
      setError(e instanceof Error ? e.message : '试问历史加载失败');
    } finally {
      setHistoryLoading(false);
    }
  };

  const loadSession = async (id: string) => {
    try {
      const detail = await getTrialSession(skillId, id);
      const loaded: Turn[] = [];
      let current: Turn | null = null;
      detail.messages.forEach(m => {
        if (m.role === 'user') {
          if (current) loaded.push(current);
          current = {
            id: m.id, question: m.content, answer: '', grains: [],
            canApplyDirectly: true, published: skill?.status === 'published', streaming: false,
          };
        } else if (current) {
          current.answer = m.content;
          current.grains = (m.meta?.grainIds ?? []).map(gid => ({ id: gid }));
          current.avgSimilarity = m.meta?.avgSimilarity ?? undefined;
        }
      });
      if (current) loaded.push(current);
      // 历史里的颗粒只有 ID，补一次详情才能点开纠偏
      const last = loaded[loaded.length - 1];
      if (last && last.grains.length > 0) {
        last.grains = await Promise.all(
          last.grains.map(g => getTrialGrain(skillId, g.id).catch(() => g)));
      }
      setTurns(loaded);
      setSessionId(detail.id);
      setHistoryOpen(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : '试问会话加载失败');
    }
  };

  if (loading) {
    return <div className="flex h-64 items-center justify-center"><LoadingSpinner /></div>;
  }

  const lastTurn = turns.length > 0 ? turns[turns.length - 1] : null;
  const lastQuestion = lastTurn?.question;
  // 历史会话没有 trial 事件，用权限自己推一次：已发布分身只有管理员能直接改
  const permissions = ((getUser() as { permissions?: string[] } | null)?.permissions) ?? [];
  const canApplyDirectly = !!skill
    && (skill.status !== 'published' || permissions.includes(Permission.SKILL_MANAGE));

  return (
    // 外层 AppLayout 已有 h-14 顶栏，这里必须扣掉，否则 scrollIntoView 会把页面顶栏滚出视口
    <div className="flex h-[calc(100vh-3.5rem)] flex-col bg-[#F8FAFC]">
      {/* 顶栏 */}
      <div className="flex shrink-0 items-center justify-between border-b border-[#E8ECF1] bg-white px-6 py-3">
        <div className="flex items-center gap-3">
          <button onClick={() => router.push('/my/skills')}
            className="text-[13px] text-[#64748B] hover:text-[#1E293B]">
            ← 我的分身
          </button>
          <span className="text-[#E2E8F0]">|</span>
          <h1 className="text-[15px] font-semibold text-[#1E293B]">
            正在试问：{skill?.displayName || skill?.ownerName || '分身'}
          </h1>
          <span className="rounded-full bg-[#EEF2FF] px-2 py-0.5 text-[11px] text-[#4F46E5]">
            试问模式 · 不计入员工数据
          </span>
        </div>
        <div className="flex items-center gap-2">
          <button onClick={startNew}
            className="rounded-lg border border-[#E2E8F0] px-3 py-1.5 text-[13px] text-[#475569] hover:bg-[#F8FAFC]">
            新对话
          </button>
          <button onClick={openHistory}
            className="rounded-lg border border-[#E2E8F0] px-3 py-1.5 text-[13px] text-[#475569] hover:bg-[#F8FAFC]">
            试问历史
          </button>
        </div>
      </div>

      {skill && skill.status !== 'published' && (
        <div className="shrink-0 bg-amber-50 px-6 py-2 text-[12px] text-amber-800">
          这个分身还没发布，只有你和管理员能看到。这里问的话不会进入员工对话记录，发布前的修改也不会影响别人。
        </div>
      )}

      {error && (
        <div className="shrink-0 bg-red-50 px-6 py-2 text-[12px] text-red-600">{error}</div>
      )}

      <div className="flex min-h-0 flex-1">
        {/* 左：问答 */}
        <div className="flex min-w-0 flex-1 flex-col">
          <div className="flex-1 overflow-y-auto px-6 py-5">
            {turns.length === 0 ? (
              <div className="mx-auto mt-16 max-w-[520px] rounded-2xl bg-white p-8 text-center shadow-sm">
                <p className="text-[15px] font-medium text-[#1E293B]">先问一句你平时最常被问到的</p>
                <p className="mt-2 text-[13px] leading-relaxed text-[#64748B]">
                  比如「客户说比隔壁贵 20% 怎么办」。<br />
                  回答下面会列出它引用了你哪几条经验，觉得哪条说偏了，点一下就能改。
                </p>
                <div className="mt-5 flex flex-wrap justify-center gap-2">
                  {['客户说比隔壁贵怎么办', '客户三次改需求还改吗', '预算被砍一半怎么接'].map(q => (
                    <button key={q} onClick={() => send(q)}
                      className="rounded-full border border-[#E2E8F0] px-3 py-1.5 text-[12px] text-[#475569] hover:bg-[#F8FAFC]">
                      {q}
                    </button>
                  ))}
                </div>
              </div>
            ) : (
              <div className="mx-auto max-w-[760px] space-y-6">
                {turns.map(turn => (
                  <div key={turn.id} className="space-y-3">
                    <div className="flex justify-end">
                      <div className="max-w-[80%] rounded-2xl bg-[#2563EB] px-4 py-2.5 text-[14px] leading-relaxed text-white">
                        {turn.question}
                      </div>
                    </div>
                    <div className="flex justify-start">
                      <div className="max-w-[86%] rounded-2xl bg-white px-4 py-3 text-[14px] leading-relaxed text-[#334155] shadow-sm">
                        <div className="whitespace-pre-wrap">{turn.answer || (turn.streaming ? '正在回答…' : '')}</div>
                        {turn.streaming && <span className="ml-0.5 animate-pulse">▍</span>}
                        {turn.sourceNames && (
                          <p className="mt-3 border-t border-[#F1F5F9] pt-2 text-[11px] text-[#94A3B8]">
                            依据：{turn.sourceNames}
                            {turn.avgSimilarity ? ` · 相关度 ${turn.avgSimilarity}%` : ''}
                          </p>
                        )}
                      </div>
                    </div>
                  </div>
                ))}
                <div ref={bottomRef} />
              </div>
            )}
          </div>

          {/* 输入 */}
          <div className="shrink-0 border-t border-[#E8ECF1] bg-white px-6 py-3">
            <div className="mx-auto flex max-w-[760px] items-end gap-3">
              <textarea
                value={input}
                onChange={e => setInput(e.target.value)}
                onKeyDown={e => {
                  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(input); }
                }}
                rows={2}
                placeholder="试问一句…（Enter 发送，Shift+Enter 换行）"
                className="flex-1 resize-none rounded-xl border border-[#E2E8F0] px-3 py-2 text-[14px] outline-none focus:border-[#2563EB]"
              />
              <button onClick={() => send(input)} disabled={streaming || !input.trim()}
                className="rounded-lg bg-[#2563EB] px-5 py-2 text-[13px] font-medium text-white disabled:opacity-40">
                发送
              </button>
            </div>
          </div>
        </div>

        {/* 右：引用经验 */}
        <div className="w-[380px] shrink-0 overflow-y-auto border-l border-[#E8ECF1] bg-white px-4 py-4">
          <h2 className="text-[13px] font-semibold text-[#1E293B]">本次引用的经验</h2>
          <p className="mt-1 text-[11px] text-[#94A3B8]">分身回答问题前，从这些经验里取的判断</p>

          {!lastTurn && (
            <div className="mt-6 rounded-xl bg-[#F8FAFC] px-4 py-6 text-center text-[12px] text-[#94A3B8]">
              先问一个问题
            </div>
          )}

          {lastTurn && !lastTurn.streaming && lastTurn.grains.length === 0 && (
            <div className="mt-4 rounded-xl bg-[#F8FAFC] px-4 py-4 text-[12px] leading-relaxed text-[#64748B]">
              本次没有引用到你的经验，回答来自通用能力。
              <span className="mt-1 block text-[#94A3B8]">
                这块是知识缺口，可以在「数据看板 → 知识缺口」里补一条经验。
              </span>
            </div>
          )}

          <div className="mt-3 space-y-2">
            {lastTurn?.grains.map((g, index) => (
              <button key={g.id} onClick={() => setActiveGrain(g)}
                className="block w-full rounded-xl border border-[#E8ECF1] px-3 py-3 text-left transition-colors hover:border-[#BFDBFE] hover:bg-[#F8FAFF]">
                <div className="flex items-center justify-between">
                  <span className="rounded-full bg-[#EEF2FF] px-2 py-0.5 text-[11px] text-[#4F46E5]">
                    {g.sceneTag || `经验 ${index + 1}`}
                  </span>
                  {g.qualityScore != null && (
                    <span className="text-[11px] text-[#94A3B8]">质量 {Math.round(g.qualityScore)}</span>
                  )}
                </div>
                {g.sceneDescription && (
                  <p className="mt-1.5 line-clamp-2 text-[12px] leading-relaxed text-[#475569]">
                    {g.sceneDescription}
                  </p>
                )}
                <p className="mt-2 text-[11px] font-medium text-[#2563EB]">改这条 →</p>
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* 纠偏抽屉 */}
      {activeGrain && skill && (
        <RefineDrawer
          skillId={skillId}
          grain={activeGrain}
          published={skill.status === 'published'}
          canApplyDirectly={lastTurn?.canApplyDirectly ?? canApplyDirectly}
          lastQuestion={lastQuestion}
          onClose={() => setActiveGrain(null)}
          onApplied={() => { /* 抽屉内部展示状态；这里只保留刷新入口 */ }}
          onRetest={question => { setActiveGrain(null); send(question); }}
        />
      )}

      {/* 试问历史 */}
      {historyOpen && (
        <div className="fixed inset-0 z-40 flex justify-end bg-black/20">
          <div className="flex h-full w-[420px] flex-col bg-white shadow-2xl">
            <div className="flex items-center justify-between border-b border-[#EDF1F6] px-5 py-4">
              <h2 className="text-[14px] font-semibold text-[#1E293B]">试问历史</h2>
              <button onClick={() => setHistoryOpen(false)}
                className="rounded-lg px-2 py-1 text-[#94A3B8] hover:bg-[#F1F5F9]">✕</button>
            </div>
            <div className="flex-1 overflow-y-auto px-3 py-3">
              {historyLoading ? (
                <div className="flex h-32 items-center justify-center"><LoadingSpinner /></div>
              ) : sessions.length === 0 ? (
                <p className="mt-8 text-center text-[13px] text-[#94A3B8]">还没有试问记录</p>
              ) : (
                sessions.map(s => (
                  <button key={s.id} onClick={() => loadSession(s.id)}
                    className="mb-2 block w-full rounded-xl px-3 py-3 text-left hover:bg-[#F8FAFC]">
                    <p className="truncate text-[13px] text-[#334155]">
                      {s.firstQuestion || '（无内容）'}
                    </p>
                    <p className="mt-1 text-[11px] text-[#94A3B8]">
                      {s.messageCount} 条消息 · {new Date(s.updatedAt).toLocaleString()}
                    </p>
                  </button>
                ))
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
