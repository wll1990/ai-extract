'use client';
import React from 'react';
import { SkillChatView } from '@/components/skill/SkillChatView';
import { useDemoFlow, type ScenarioInfo, type ChatMessage, type Mode } from './useDemoFlow';
import type { GrainTrace } from '@/lib/api/audit';

// ---- Match styles ----

const MATCH_STYLES: Record<string, { icon: string; border: string; bg: string; text: string; label: string }> = {
  EXACT: { icon: '📋', border: 'border-green-300', bg: 'bg-green-50', text: 'text-green-700', label: '精确命中' },
  SEMANTIC: { icon: '🔗', border: 'border-blue-300', bg: 'bg-blue-50', text: 'text-blue-700', label: '语义相关' },
  PROFILE_GUESS: { icon: '💡', border: 'border-yellow-300', bg: 'bg-yellow-50', text: 'text-yellow-700', label: '画像推断' },
  NO_DATA: { icon: '⚠️', border: 'border-red-300', bg: 'bg-red-50', text: 'text-red-700', label: '数据不足' },
  NON_BUSINESS: { icon: '🚫', border: 'border-border-strong', bg: 'bg-surface', text: 'text-muted-foreground', label: '非业务' },
};

interface Props {
  skillId: string;
  skill: { ownerName?: string; ownerTitle?: string; department?: string; seniority?: string; displayName?: string; };
  profile?: { personality?: string; speakingStyle?: string; background?: string; };
  scenarioGrains: Record<string, Array<{ id: string; sceneTag: string; sceneDescription: string; qualityScore?: number; expertThought: string; standardScript: string; commonMistakes: string; }>>;
  isPublished?: boolean;
  onClose: () => void;
}

// ====== Sub-components ======

function TraceView({ msg }: { msg: ChatMessage }) {
  if (msg.grains && msg.grains.length > 0) return (
    <details className="mt-1 px-2"><summary className="text-[10px] text-gray-300 cursor-pointer hover:text-muted-foreground">📋 溯源 · {msg.grains.length}条</summary>
      <div className="mt-1 space-y-1">{msg.grains.map((g, gi) => {
        const s = MATCH_STYLES[g.matchLevel || 'SEMANTIC'] || MATCH_STYLES.SEMANTIC;
        return <div key={gi} className={`text-[10px] ${s.text} ${s.bg} rounded px-2 py-1`}>{s.icon} {g.sceneTag}{g.fileName && ` · ${g.fileName}`}{g.qualityScore != null && ` · ⭐${g.qualityScore}`}</div>;
      })}</div></details>);
  const s = MATCH_STYLES[msg.matchLevel || 'PROFILE_GUESS'] || MATCH_STYLES.PROFILE_GUESS;
  return <div className={`mt-1 text-[10px] ${s.text} px-2`}>{s.icon} {s.label}</div>;
}

function RoleLabel({ mode, sceneTag, role }: { mode: Mode; sceneTag?: string; role: 'customer' | 'avatar' }) {
  if (mode === 'practice') return <>{role === 'customer' ? '👤 销售（你）' : `👤 客户${sceneTag ? ' · ' + sceneTag : ''}`}</>;
  if (mode === 'qa') return <>{role === 'customer' ? '👤 你' : '🤖 分身'}</>;
  return <>{role === 'customer' ? '👤 客户' : '🤖 分身（销冠）'}</>;
}

// ====== Main Component ======

export default function ProductDemoModal({ skillId, skill, scenarioGrains, onClose }: Props) {
  const ownerName = skill.ownerName || skill.displayName || '未命名';
  const flow = useDemoFlow(skillId, scenarioGrains, ownerName);
  const { state, dispatch } = flow;

  // ---- Render: Scenes ----
  if (state.phase === 'scenes') return (
    <Modal onClose={onClose}>
      <ModalHeader title="🔍 选择验证场景" onClose={onClose} />
      <div className="flex-1 overflow-y-auto p-6 space-y-3">
        {flow.sceneList.length === 0 ? <p className="text-sm text-muted-foreground-2 text-center py-10">暂无场景颗粒数据</p> :
          flow.sceneList.map(s => (
            <button key={s.tag} onClick={() => flow.actions.selectScene(s)} className="w-full text-left bg-surface-2 border rounded-xl p-4 hover:border-primary hover:shadow-sm transition group">
              <div className="flex items-center justify-between"><h3 className="font-semibold text-sm text-gray-800 group-hover:text-primary">{s.tag}</h3>
                <div className="flex items-center gap-3 text-xs text-muted-foreground-2">
                  <span>{s.grainCount}条颗粒</span>
                  {s.avgScore != null && <span className="text-amber-500">⭐{s.avgScore.toFixed(1)}</span>}
                  <span className="text-primary opacity-0 group-hover:opacity-100">开始 →</span>
                </div>
              </div>
              {s.description && <p className="text-xs text-muted-foreground mt-1 line-clamp-2">{s.description}</p>}
            </button>
          ))}
      </div>
    </Modal>
  );

  // ---- Render: Mode Select ----
  if (state.phase === 'mode-select' && state.currentScene) return (
    <Modal onClose={onClose}>
      <div className="flex items-center justify-between px-6 py-4 border-b">
        <div><button onClick={() => { const s = flow.sceneList[0]; if (s) dispatch({ type: 'SELECT_SCENE', scene: s }); }} className="text-xs text-muted-foreground-2 hover:text-muted-foreground">← 返回场景列表</button>
          <h2 className="text-lg font-bold mt-0.5">{state.currentScene.tag}</h2></div>
        <button onClick={onClose} className="text-muted-foreground-2 hover:text-muted-foreground text-xl">✕</button>
      </div>
      <div className="flex-1 overflow-y-auto p-6">
        <p className="text-sm text-muted-foreground mb-4">{state.currentScene.description?.substring(0, 100)}</p>
        <ModeGrid onSelect={flow.actions.startMode} />
      </div>
    </Modal>
  );

  // ---- Render: Chat ----
  const { currentScene } = state;
  return (
    <Modal onClose={onClose}>
      {/* Header */}
      <div className="flex items-center justify-between px-6 py-3 border-b shrink-0">
        <div className="flex items-center gap-2 min-w-0">
          <button onClick={() => { flow.abortRef.current?.abort(); dispatch({ type: 'BACK_TO_MODE_SELECT' }); }} className="text-xs text-muted-foreground-2 hover:text-muted-foreground shrink-0">← 返回</button>
          <span className="text-sm font-semibold truncate">{state.mode === 'practice' ? '🎭 对练' : state.mode === 'qa' ? '💬 问答' : state.mode === 'demo' ? '🎬 快速演示' : '🔧 完整调试'}<span className="text-muted-foreground-2 font-normal ml-1">· {currentScene?.tag}</span></span>
        </div>
        <button onClick={onClose} className="text-muted-foreground-2 hover:text-muted-foreground text-xl shrink-0">✕</button>
      </div>

      {/* QA Recommended Questions */}
      {state.mode === 'qa' && flow.ui.recQuestions.length > 0 && <QaQuestions recQuestions={flow.ui.recQuestions} showAll={flow.ui.showAllQuestions} setShowAll={flow.ui.setShowAllQuestions} onSend={flow.qa.sendQaMessage} />}

      {/* Round counter */}
      {state.mode === 'practice' && state.totalAngles > 0 && (
        <div className="text-center text-xs text-muted-foreground-2 py-1.5 bg-surface border-b">练习角度 {state.currentAngle}/{state.totalAngles}</div>
      )}

      <SkillChatView
        inputValue={flow.ui.input} onInputChange={flow.ui.setInput}
        onSend={() => state.mode === 'practice' ? flow.practice.sendPractice() : flow.qa.sendQa()}
        isStreaming={flow.ui.streaming} streamText={flow.ui.streamText}
        ownerName={ownerName}
        placeholder={state.mode === 'practice' ? '输入你的销售回复...' : '输入你的问题...'}
        disabled={state.autoRunning || (state.mode !== 'practice' && state.mode !== 'qa')}
      >
        {state.messages.map((m, i) => (
          <div key={m.id || `cm-${i}`} className={`flex ${m.role === 'customer' ? 'justify-end' : 'justify-start'}`}>
            <div className="max-w-[80%]">
              <div className={`rounded-xl px-4 py-2.5 text-sm ${m.role === 'customer' ? 'bg-primary-light text-gray-800' : 'bg-blue-50 text-gray-800'}`}>
                <p className={`text-xs mb-1 ${m.role === 'customer' ? 'text-muted-foreground-2' : 'text-blue-400'}`}><RoleLabel mode={state.mode} sceneTag={currentScene?.tag} role={m.role} /></p>
                {state.mode === 'qa' && m.role === 'avatar' ? <span className="whitespace-pre-wrap break-words">{m.content}</span> : m.content}
              </div>
              {m.role === 'avatar' && state.mode !== 'demo' && <TraceView msg={m} />}
              {state.mode === 'practice' && m.role === 'customer' && m.championAnswer && (
                <PracticeEvalCard msg={m} allMessages={state.messages} msgIndex={i} streaming={flow.ui.streaming} autoRunning={state.autoRunning}
                  onRetry={flow.practice.retryPractice} onAdvance={flow.practice.advanceRound} />
              )}
            </div>
          </div>
        ))}
        {flow.ui.streaming && flow.ui.streamText && (
          <div className="flex justify-start"><div className="max-w-[80%] rounded-xl px-4 py-2.5 bg-blue-50 text-gray-800 text-sm"><p className="text-xs text-blue-400 mb-1">🤖 分身 · 回复中...</p>{flow.ui.streamText}</div></div>
        )}
        {state.autoRunning && <div className="text-center text-xs text-muted-foreground-2 py-2">{state.mode === 'debug' ? '🔧 逐轮验证中...' : '🎬 演示进行中...'}</div>}
      </SkillChatView>

      {/* Bottom bar */}
      <div className="flex items-center justify-between px-6 py-2.5 border-t bg-surface shrink-0">
        <div className="flex gap-2">
          <button onClick={() => flow.actions.switchScene('prev')} disabled={!flow.nav.canPrev} className="px-2.5 py-1 border rounded text-xs text-muted-foreground disabled:opacity-30 hover:bg-surface-2">← 上个场景</button>
          <button onClick={() => flow.actions.switchScene('next')} disabled={!flow.nav.canNext} className="px-2.5 py-1 border rounded text-xs text-muted-foreground disabled:opacity-30 hover:bg-surface-2">下个场景 →</button>
        </div>
        {state.autoRunning && state.mode === 'debug' && (
          <button onClick={() => { flow.abortRef.current?.abort(); dispatch({ type: 'SET_AUTO_RUNNING', running: false }); }} className="px-4 py-1.5 bg-red-500 text-white rounded-lg text-xs">⏹ 停止验证</button>
        )}
        {!state.autoRunning && state.messages.length > 0 && (
          <button onClick={flow.actions.endAndEvaluate} disabled={flow.ui.evalLoading}
            className="px-4 py-1.5 bg-primary text-white rounded-lg text-xs disabled:opacity-40">{flow.ui.evalLoading ? '生成中...' : (state.mode === 'debug' ? '📊 验收报告' : state.mode === 'demo' ? '📋 演示评估' : '📋 结束对练')}</button>
        )}
      </div>

      {/* Evaluate Overlays */}
      {state.phase === 'evaluate' && flow.state.evalResult && (
        <EvaluateOverlay mode={state.mode} evalResult={state.evalResult} onCloseOverlay={() => dispatch({ type: 'BACK_TO_CHAT' })}
          onRetry={() => { dispatch({ type: 'BACK_TO_CHAT' }); dispatch({ type: 'SET_MESSAGES', messages: [] }); dispatch({ type: 'SET_ANGLE', angle: 1 }); }}
          onStartAuto={() => { dispatch({ type: 'BACK_TO_CHAT' }); }}
          currentScene={currentScene} skillId={skillId} onClose={onClose} />
      )}
    </Modal>
  );
}

// ====== Shared UI Components ======

function Modal({ children, onClose }: { children: React.ReactNode; onClose: () => void }) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onClick={onClose}>
      <div className="relative bg-surface-2 rounded-2xl w-full max-w-3xl max-h-[90vh] flex flex-col shadow-2xl" onClick={e => e.stopPropagation()}>
        {children}
      </div>
    </div>
  );
}

function ModalHeader({ title, onClose }: { title: string; onClose: () => void }) {
  return (
    <div className="flex items-center justify-between px-6 py-4 border-b">
      <h2 className="text-lg font-bold">{title}</h2>
      <button onClick={onClose} className="text-muted-foreground-2 hover:text-muted-foreground text-xl">✕</button>
    </div>
  );
}

function ModeGrid({ onSelect }: { onSelect: (m: Mode) => void }) {
  const modes: Array<{ key: Mode; icon: string; title: string; desc: string; detail: string }> = [
    { key: 'practice', icon: '🎭', title: '对练', desc: '刻意练习，找盲区', detail: '你扮演销冠，AI扮演客户。回答后看销冠会怎么说。' },
    { key: 'qa', icon: '💬', title: '问答', desc: '深度学习，理解逻辑', detail: '向分身提问，追问"为什么"，深入理解销冠思维。' },
    { key: 'demo', icon: '🎬', title: '快速演示', desc: '产品演示，给客户看', detail: '4-5轮精炼对话，有起承转合，自动播放。' },
    { key: 'debug', icon: '🔧', title: '完整调试', desc: '质量审查，给自己看', detail: '所有颗粒逐个验证，完整溯源和验收报告。' },
  ];
  return (
    <div className="grid grid-cols-2 gap-4">
      {modes.map(item => (
        <button key={item.key} onClick={() => onSelect(item.key)} className="p-5 rounded-xl border-2 border-border text-left hover:border-primary hover:shadow-md transition group">
          <div className="text-3xl mb-3">{item.icon}</div>
          <div className="font-bold text-sm mb-1 group-hover:text-primary">{item.title}</div>
          <div className="text-xs text-muted-foreground-2 font-medium">{item.desc}</div>
          <div className="text-[11px] text-gray-300 mt-2">{item.detail}</div>
        </button>
      ))}
    </div>
  );
}

function QaQuestions({ recQuestions, showAll, setShowAll, onSend }: {
  recQuestions: string[]; showAll: boolean; setShowAll: (v: boolean) => void;
  onSend: (q: string) => void;
}) {
  const grouped: Record<string, string[]> = {};
  recQuestions.forEach(q => { const m = q.match(/「(.+?)」/); const tag = m ? m[1] : '其他'; if (!grouped[tag]) grouped[tag] = []; grouped[tag].push(q); });
  const tags = Object.keys(grouped);
  return (
    <div className="px-6 py-2.5 bg-surface border-b shrink-0">
      <p className="text-[10px] text-muted-foreground-2 mb-1.5">💡 试试这些问题（基于分身所有技能生成）</p>
      <div className={showAll ? 'max-h-48 overflow-y-auto' : ''}>
        {showAll ? (
          <div className="space-y-3">{tags.map(tag => (
            <div key={tag}><p className="text-[10px] text-muted-foreground-2 mb-1 ml-1">{tag}</p>
              <div className="flex gap-1.5 flex-wrap">{grouped[tag].map((q, i) => (
                <button key={i} onClick={() => { setShowAll(false); onSend(q); }} className="whitespace-nowrap px-2.5 py-1 bg-surface-2 border border-border rounded text-[11px] text-foreground hover:border-primary hover:text-primary hover:bg-primary-light transition">{q.replace(/^.*?「.+?」/, '').replace(/^[的，,]?/, '').substring(0, 24)}{q.length > 28 ? '…' : ''}</button>
              ))}</div>
            </div>
          ))}</div>
        ) : (
          <div className="flex gap-1.5 flex-wrap">{recQuestions.slice(0, 5).map((q, i) => (
            <button key={i} onClick={() => { onSend(q); }} className="whitespace-nowrap px-3 py-1.5 bg-surface-2 border border-border rounded-full text-xs text-foreground hover:border-primary hover:text-primary hover:bg-primary-light transition shrink-0 font-medium">{q.substring(0, 35)}{q.length > 35 ? '...' : ''}</button>
          ))}</div>
        )}
      </div>
      {recQuestions.length > 5 && <button onClick={() => setShowAll(!showAll)} className="mt-1.5 text-[10px] text-primary hover:underline">{showAll ? '收起 ▲' : `更多 (${recQuestions.length - 5}) ▸`}</button>}
    </div>
  );
}

function PracticeEvalCard({ msg, allMessages, msgIndex, streaming, autoRunning, onRetry, onAdvance }: {
  msg: ChatMessage; allMessages: ChatMessage[]; msgIndex: number; streaming: boolean; autoRunning: boolean;
  onRetry: () => void; onAdvance: () => void;
}) {
  return (
    <div className={`mt-2 p-3 rounded-lg space-y-2 ${msg.offTopic ? 'bg-blue-50 border border-blue-200' : 'bg-amber-50 border border-amber-200'}`}>
      {msg.offTopic ? (
        <>
          <p className="text-xs font-semibold text-blue-600">💡 教练提醒</p>
          <p className="text-xs text-muted-foreground">{msg.comparison}</p>
          {msg.misses && msg.misses.length > 0 && <div className="text-[10px] text-blue-500"><span>建议关注：</span>{msg.misses.map((ms, mi) => <span key={mi}>·{ms} </span>)}</div>}
        </>
      ) : (
        <>
          <p className="text-xs font-semibold text-amber-700">⭐ 销冠会怎么说</p>
          <p className="text-xs text-foreground">{msg.championAnswer}</p>
          {msg.hits && msg.hits.length > 0 && <div className="text-[10px] space-y-0.5"><span className="text-green-600 font-medium">✅ 你说到的：</span>{msg.hits.map((h, hi) => <span key={hi} className="text-green-600 ml-1">·{h} </span>)}</div>}
          {msg.misses && msg.misses.length > 0 && <div className="text-[10px] space-y-0.5"><span className="text-amber-600 font-medium">💡 进阶建议：</span>{msg.misses.map((ms, mi) => <span key={mi} className="text-red-500 ml-1">·{ms} </span>)}</div>}
          {msg.comparison && <p className="text-[10px] text-muted-foreground pt-1 border-t border-amber-100">📝 {msg.comparison}</p>}
        </>
      )}
      {msg.technique && <div className="px-2 py-1.5 bg-purple-50 border border-purple-200 rounded text-[10px] text-purple-700">🏷️ 技法：{msg.technique}</div>}
      {!streaming && !autoRunning && msg.isLastRetry && msg.fullAnswer && (
        <div className="p-2 bg-green-50 border border-green-200 rounded-lg">
          <p className="text-xs font-semibold text-green-700 mb-1">📋 完整答案（所有技法角度）</p>
          <p className="text-xs text-foreground leading-relaxed whitespace-pre-wrap">{msg.fullAnswer}</p>
          <p className="text-[10px] text-green-600 mt-1">以上涵盖了该场景的全部技法要点，可以作为参考模板。</p>
        </div>
      )}
      {!streaming && !autoRunning && !allMessages.slice(msgIndex + 1).some(lm => lm.role === 'customer' && lm.championAnswer) && (
        <div className="flex gap-2 pt-1">
          {!msg.isLastRetry && (msg.retryCount || 0) < 2 && <button onClick={onRetry} className="px-3 py-1 bg-purple-100 text-purple-700 rounded text-xs hover:bg-purple-200 font-medium">🔄 用技法再试一次</button>}
          <button onClick={onAdvance} className="px-3 py-1 bg-primary-light text-muted-foreground rounded text-xs hover:bg-border">➡️ 继续下一轮</button>
        </div>
      )}
    </div>
  );
}

function EvaluateOverlay({ mode, evalResult, onCloseOverlay, onRetry, onStartAuto, currentScene, skillId, onClose }: {
  mode: Mode; evalResult: any; onCloseOverlay: () => void; onRetry: () => void; onStartAuto: () => void;
  currentScene: ScenarioInfo | null; skillId: string; onClose: () => void;
}) {
  if (mode === 'practice') return (
    <div className="absolute inset-0 z-10 bg-surface-2 flex flex-col rounded-2xl overflow-hidden">
      <div className="flex items-center justify-between px-6 py-4 border-b"><h2 className="text-lg font-bold">📋 本节回顾</h2><button onClick={onCloseOverlay} className="text-muted-foreground-2 hover:text-muted-foreground text-xl">✕</button></div>
      <div className="flex-1 overflow-y-auto p-6 space-y-4">
        {evalResult.techniqueDetails?.length > 0 && (
          <div className="space-y-2"><h4 className="text-sm font-semibold text-muted-foreground">🏷️ 你带走的技法</h4>
            {evalResult.techniqueDetails.map((td: any, tdi: number) => {
              const cfg = td.status === 'mastered' ? { icon: '✅', bg: 'bg-green-50', text: 'text-green-700', label: '已掌握' } : td.status === 'improving' ? { icon: '🔄', bg: 'bg-amber-50', text: 'text-amber-700', label: '再试后改善' } : { icon: '📌', bg: 'bg-surface', text: 'text-muted-foreground', label: '下次练习' };
              return <div key={tdi} className={`${cfg.bg} rounded-lg p-3`}><div className="flex items-center gap-2 mb-1"><span className="text-xs font-semibold">{cfg.icon} {cfg.label}</span></div><p className={`text-xs ${cfg.text}`}>{td.technique}</p></div>;
            })}
          </div>
        )}
        {evalResult.tryNext?.length > 0 && <div className="space-y-2"><h4 className="text-sm font-semibold text-muted-foreground">💡 下次试试</h4>{evalResult.tryNext.map((tip: string, ti: number) => <p key={ti} className="text-xs text-muted-foreground bg-purple-50 rounded-lg px-3 py-2">💡 {tip}</p>)}</div>}
        {evalResult.relatedScenes?.length > 0 && <div><h4 className="text-sm font-semibold text-muted-foreground mb-2">🔗 推荐继续练</h4><div className="flex gap-2 flex-wrap">{evalResult.relatedScenes.map((s: string, si: number) => <span key={si} className="px-3 py-1 bg-surface border border-border rounded-full text-xs text-muted-foreground">{s}</span>)}</div></div>}
        {evalResult.retryCount > 0 && <p className="text-xs text-muted-foreground-2 text-center">本节你用「再试一次」改进了 {evalResult.retryCount} 次回答</p>}
        {evalResult.suggestion && <p className="text-sm text-muted-foreground italic text-center border-t pt-3">💡 {evalResult.suggestion}</p>}
      </div>
      <div className="flex justify-center gap-3 px-6 py-4 border-t">
        <button onClick={onRetry} className="px-5 py-2 border border-primary text-primary rounded-lg text-sm">🔄 再练一次</button>
        <button onClick={onClose} className="px-5 py-2 bg-primary text-white rounded-lg text-sm">关闭</button>
      </div>
    </div>
  );

  if (mode === 'qa') return (
    <div className="absolute inset-0 z-10 bg-surface-2 flex flex-col rounded-2xl overflow-hidden">
      <div className="flex items-center justify-between px-6 py-4 border-b"><h2 className="text-lg font-bold">📊 问答报告</h2><button onClick={onCloseOverlay} className="text-muted-foreground-2 hover:text-muted-foreground text-xl">✕</button></div>
      <div className="flex-1 overflow-y-auto p-6 space-y-4">
        {evalResult.traceRate && <div className="flex items-center gap-3 text-sm"><span className="text-muted-foreground">溯源覆盖率</span><b className="text-green-600">{evalResult.traceRate}</b></div>}
        {evalResult.totalQuestions != null && <div className="text-sm text-muted-foreground">总提问: <b>{evalResult.totalQuestions}</b></div>}
        {evalResult.coveredTags?.length > 0 && <div className="text-xs text-muted-foreground-2">✅ 已覆盖: {evalResult.coveredTags.map((t: string) => <span key={t} className="bg-green-50 text-green-600 px-2 py-0.5 rounded ml-1">{t}</span>)}</div>}
        {evalResult.uncoveredTags?.length > 0 && <div className="text-xs text-muted-foreground-2">⚠️ 盲区: {evalResult.uncoveredTags.map((t: string) => <span key={t} className="bg-red-50 text-red-600 px-2 py-0.5 rounded ml-1">{t}</span>)}</div>}
        {evalResult.risks?.length > 0 && <div className="space-y-2"><h4 className="text-sm font-semibold text-muted-foreground">风险点</h4>{evalResult.risks.map((r: any, ri: number) => <div key={ri} className="border border-red-200 bg-red-50 rounded-lg p-3 text-xs text-red-700">第{r.round}轮: {r.detail}</div>)}</div>}
        {evalResult.suggestion && <p className="text-sm text-muted-foreground italic">💡 {evalResult.suggestion}</p>}
      </div>
      <div className="flex justify-center gap-3 px-6 py-4 border-t">
        <button onClick={() => { onCloseOverlay(); }} className="px-5 py-2 border border-primary text-primary rounded-lg text-sm">🔄 继续提问</button>
        <button onClick={onClose} className="px-5 py-2 bg-primary text-white rounded-lg text-sm">关闭</button>
      </div>
    </div>
  );

  if (mode === 'demo') return (
    <div className="absolute inset-0 z-10 bg-surface-2 flex flex-col rounded-2xl overflow-hidden">
      <div className="flex items-center justify-between px-6 py-4 border-b"><h2 className="text-lg font-bold">🎬 演示评估</h2><button onClick={onCloseOverlay} className="text-muted-foreground-2 hover:text-muted-foreground text-xl">✕</button></div>
      <div className="flex-1 overflow-y-auto p-6 space-y-4">
        {evalResult.verdictText && <div className={`rounded-xl p-4 ${evalResult.verdict === 'ready' ? 'bg-green-50 border border-green-200' : 'bg-amber-50 border border-amber-200'}`}><div className="font-bold text-lg">{evalResult.verdict === 'ready' ? '✅' : '⚠️'} {evalResult.verdictText}</div></div>}
        {evalResult.traceCoverage && <div className="text-sm text-muted-foreground">溯源覆盖率 <b className="text-green-600">{evalResult.traceCoverage.rate}%</b> — {evalResult.traceCoverage.detail}</div>}
        {evalResult.suggestion && <div className="bg-blue-50 border border-blue-200 rounded-xl p-4"><p className="text-xs text-blue-700 font-medium mb-1">📌 建议</p><p className="text-sm text-blue-800">{evalResult.suggestion}</p></div>}
      </div>
      <div className="flex justify-center gap-3 px-6 py-4 border-t">
        <button onClick={onStartAuto} className="px-5 py-2 border border-primary text-primary rounded-lg text-sm">🔄 再演示一次</button>
        <button onClick={onClose} className="px-5 py-2 bg-primary text-white rounded-lg text-sm">关闭</button>
      </div>
    </div>
  );

  return (
    <div className="absolute inset-0 z-10 bg-surface-2 flex flex-col rounded-2xl overflow-hidden">
      <div className="flex items-center justify-between px-6 py-4 border-b"><h2 className="text-lg font-bold">📊 验收报告</h2><button onClick={onCloseOverlay} className="text-muted-foreground-2 hover:text-muted-foreground text-xl">✕</button></div>
      <div className="flex-1 overflow-y-auto p-6 space-y-4">
        {evalResult.verdictText && <div className={`rounded-xl p-4 ${evalResult.verdict === 'ready' ? 'bg-green-50 border border-green-200' : evalResult.verdict === 'review' ? 'bg-amber-50 border border-amber-200' : 'bg-red-50 border border-red-200'}`}><div className={`font-bold ${evalResult.verdict === 'ready' ? 'text-green-700' : evalResult.verdict === 'review' ? 'text-amber-700' : 'text-red-700'}`}>{evalResult.verdictText}</div></div>}
        {evalResult.traceCoverage && <div className="flex items-center gap-3 text-sm"><span className="text-muted-foreground">溯源覆盖率</span><b className="text-green-600">{evalResult.traceCoverage.rate}%</b><span className="text-xs text-muted-foreground-2">({evalResult.traceCoverage.detail})</span></div>}
        {evalResult.skillCoverage?.length > 0 && <div className="space-y-2"><h4 className="text-sm font-semibold text-muted-foreground">技能覆盖矩阵</h4><div className="flex gap-1.5 flex-wrap">{evalResult.skillCoverage.map((sc: any) => <span key={sc.tag} className={`px-2.5 py-1 rounded-full text-[11px] font-medium ${sc.status === 'covered' ? 'bg-green-100 text-green-700' : sc.status === 'partial' ? 'bg-amber-100 text-amber-700' : 'bg-red-50 text-red-400'}`}>{sc.status === 'missing' ? '○ ' : '● '}{sc.tag}</span>)}</div></div>}
        {evalResult.risks?.length > 0 && <div className="space-y-2"><h4 className="text-sm font-semibold text-muted-foreground">风险标注</h4>{evalResult.risks.map((r: any, ri: number) => <div key={ri} className="border border-red-200 bg-red-50 rounded-lg p-3 text-xs text-red-700"><span className="font-medium">{r.type}</span> — {r.detail}</div>)}</div>}
        {evalResult.roundReviews?.length > 0 && <details className="space-y-2" open><summary className="text-sm font-semibold text-muted-foreground cursor-pointer">逐轮回放 · {evalResult.roundReviews.length}轮</summary><div className="mt-2 space-y-2">{evalResult.roundReviews.map((rd: any) => <div key={rd.round} className={`border rounded-lg p-3 text-xs ${rd.traceable ? 'border-green-200 bg-green-50/30' : 'border-border bg-surface'}`}><div className="flex items-center gap-2 mb-1"><span className="font-semibold text-muted-foreground">第{rd.round}轮</span><span>{rd.traceable ? '✅ 溯源成功' : '⚠️ 未命中'}</span>{rd.matchedSceneTag && <span className="text-green-600">· {rd.matchedSceneTag}</span>}<span className="text-gray-300">· {rd.matchLevel}</span></div><p className="text-muted-foreground-2 mt-1 line-clamp-2">客户: {rd.customerMsg?.substring(0, 80)}</p><p className="text-muted-foreground-2 line-clamp-2">分身: {rd.avatarMsg?.substring(0, 80)}</p></div>)}</div></details>}
        {evalResult.suggestion && <div className="bg-blue-50 border border-blue-200 rounded-xl p-4"><p className="text-xs text-blue-700 font-medium mb-1">📌 下一步建议</p><p className="text-sm text-blue-800">{evalResult.suggestion}</p></div>}
      </div>
      <div className="flex justify-center gap-3 px-6 py-4 border-t">
        <button onClick={onStartAuto} className="px-5 py-2 border border-primary text-primary rounded-lg text-sm">🔄 重新验证</button>
        <button onClick={onClose} className="px-5 py-2 bg-primary text-white rounded-lg text-sm">关闭</button>
      </div>
    </div>
  );
}
