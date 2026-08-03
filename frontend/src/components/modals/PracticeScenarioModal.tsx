'use client';
import React, { useState, useRef, useEffect } from 'react';
import { getToken } from '@/lib/storage';
import { API_BASE } from '@/lib/api/client';
import { connectSse } from '@/lib/sse';
import { SkillChatView } from '@/components/skill/SkillChatView';

type GrainInfo = {
  id?: string; sceneTag?: string; sceneDescription?: string;
  expertThought?: string; standardScript?: string; commonMistakes?: string;
  qualityScore?: number;
};

interface Props {
  skillId: string;
  grain: GrainInfo;
  grains: GrainInfo[];
  grainIdx: number;
  onPrev: () => void;
  onNext: () => void;
  onClose: () => void;
}

type Message = { role: 'customer' | 'sales'; content: string; analysis?: string; skillMode?: 'with_skill' | 'without_skill' };

function buildOpening(sceneDescription: string): string {
  if (!sceneDescription) return '你好，能介绍一下你们的产品吗？';
  // 从场景描述中提取关键信息，构建客户开场
  const desc = sceneDescription;
  if (desc.includes('价格') || desc.includes('报价') || desc.includes('竞品')) {
    return '我看了一下你们的方案，说实话报价比竞品贵了不少，我们内部也在讨论要不要继续。';
  }
  if (desc.includes('异议') || desc.includes('质疑') || desc.includes('反对')) {
    return '你们这个方案有几个地方我不太满意，特别是XX这块，能不能解释一下？';
  }
  if (desc.includes('逼单') || desc.includes('犹豫') || desc.includes('迟迟')) {
    return '方案我看过了，整体还行，但还想再比较两家再做决定。';
  }
  if (desc.includes('付款') || desc.includes('预算') || desc.includes('贵')) {
    return '这个价格我们预算确实有点紧，能不能在付款方式上灵活一些？';
  }
  if (desc.includes('破冰') || desc.includes('接触') || desc.includes('戒备')) {
    return '你好，听朋友说起过你们，但说实话我对这个行业不太了解，你先简单介绍下？';
  }
  if (desc.includes('跟进') || desc.includes('不回复') || desc.includes('冷淡')) {
    return '之前小张联系过我几次，我一直没顾上回复。说实话我们现在也有供应商，暂时不缺。';
  }
  // fallback: 用场景描述的前半句作为客户开场
  const parts = desc.split(/[,，。]/);
  const prefix = parts[0] || desc;
  return `你好，我这边是做${prefix}的，最近在考虑换方案，你能介绍一下吗？`;
}

export default function PracticeScenarioModal({ skillId, grain, grains, grainIdx, onPrev, onNext, onClose }: Props) {
  const [mode, setMode] = useState<'with_skill' | 'without_skill'>('with_skill');
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const [streamText, setStreamText] = useState('');
  const [streamAnalysis, setStreamAnalysis] = useState('');
  const [sourceData, setSourceData] = useState<any>(null);
  const started = useRef(false);

  // 默认第一轮（防 StrictMode 双重执行）
  useEffect(() => {
    if (!started.current) {
      started.current = true;
      startRound(buildOpening(grain.sceneDescription || ''));
    }
    // 加载溯源
    const token = getToken();
    fetch(`${API_BASE}/admin/skills/${skillId}/grain-source?grainId=${grain.id}`, {
      headers: { Authorization: `Bearer ${token}` }
    }).then(r => r.json()).then(d => {
      console.log('source data:', d);
      if (d.code === 200 && d.data?.sourceExcerpt) setSourceData(d.data);
    }).catch(() => {});
  }, []);

  const startRound = (customerMsg: string, overrideMode?: 'with_skill' | 'without_skill', reset = false) => {
    const currentMode = overrideMode || mode;
    const history = reset ? [] : [...messages];
    if (customerMsg) {
      history.push({ role: 'customer', content: customerMsg });
      setMessages([...history]);
    }

    setStreaming(true);
    setStreamText('');
    setStreamAnalysis('');

    let reply = '';
    connectSse(
      {
        url: `${API_BASE}/admin/skills/${skillId}/practice-scenario`,
        method: 'POST',
        body: {
          grainId: grain.id,
          mode: currentMode,
          customerMessage: customerMsg,
          history: history.map(m => ({ role: m.role, content: m.content })),
        },
      },
      {
        onChunk: (text) => {
          reply += text;
          setStreamText(reply);
        },
        onDone: () => {
          setMessages(prev => [...prev, { role: 'sales', content: reply, skillMode: currentMode }]);
          setStreamText('');
          setStreamAnalysis('');
          setStreaming(false);
        },
        onError: () => {
          setStreaming(false);
        },
      },
    );
  };

  const handleSend = () => {
    if (!input.trim() || streaming) return;
    const msg = input.trim();
    setInput('');
    startRound(msg);
  };

  const handleNewRound = () => {
    startRound(buildOpening(grain.sceneDescription || ''), mode, true);
  };

  const handleModeSwitch = (m: typeof mode) => {
    setMode(m);
    startRound(buildOpening(grain.sceneDescription || ''), m, true);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onClick={onClose}>
      <div className="bg-surface-2 rounded-2xl w-full max-w-2xl max-h-[85vh] flex flex-col shadow-2xl" onClick={e => e.stopPropagation()}>
        {/* Header */}
        <div className="px-6 py-3 border-b">
          {/* 顶行：返回 + 场景导航 + 关闭 */}
          <div className="flex items-center justify-between mb-2">
            <div className="flex items-center gap-3">
              <button onClick={onClose} className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors">
                <span>←</span> 返回场景列表
              </button>
              {/* 场景导航 */}
              <div className="flex items-center gap-1 ml-2 pl-2 border-l">
                <button onClick={onPrev} disabled={grainIdx === 0}
                  className="px-2 py-0.5 text-xs border rounded hover:bg-surface disabled:opacity-20 disabled:cursor-not-allowed">◀</button>
                <span className="text-xs text-muted-foreground-2 min-w-[36px] text-center">{grainIdx + 1}/{grains.length}</span>
                <button onClick={onNext} disabled={grainIdx >= grains.length - 1}
                  className="px-2 py-0.5 text-xs border rounded hover:bg-surface disabled:opacity-20 disabled:cursor-not-allowed">▶</button>
              </div>
            </div>
            <div className="flex items-center gap-3">
              {grain.qualityScore != null && (
                <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${
                  grain.qualityScore! >= 4 ? 'bg-green-100 text-green-700' : 'bg-amber-100 text-amber-700'
                }`}>⭐ {grain.qualityScore!.toFixed(1)}</span>
              )}
              <button onClick={onClose} className="text-muted-foreground-2 hover:text-muted-foreground text-lg">✕</button>
            </div>
          </div>
          {/* 标题行 */}
          <div>
            <h2 className="text-lg font-bold">🎯 场景对练：{grain.sceneTag}</h2>
            <p className="text-xs text-muted-foreground-2 mt-0.5">{grain.sceneDescription?.substring(0, 80)}</p>
          </div>
        </div>

        {/* Mode toggle */}
        <div className="flex gap-2 px-6 py-3 bg-surface border-b">
          <button onClick={() => handleModeSwitch('with_skill')}
            className={`px-4 py-2 rounded-full text-sm font-medium transition-all ${
              mode === 'with_skill'
                ? 'bg-green-500 text-white shadow-md scale-105'
                : 'bg-surface-2 text-muted-foreground-2 border border-border hover:border-green-300'
            }`}>✅ 使用销冠技能</button>
          <button onClick={() => handleModeSwitch('without_skill')}
            className={`px-4 py-2 rounded-full text-sm font-medium transition-all ${
              mode === 'without_skill'
                ? 'bg-red-500 text-white shadow-md scale-105'
                : 'bg-surface-2 text-muted-foreground-2 border border-border hover:border-red-300'
            }`}>❌ 不使用技能</button>
        </div>

        {/* Messages + Input — 共享组件 */}
        <SkillChatView
          inputValue={input}
          onInputChange={setInput}
          onSend={handleSend}
          isStreaming={streaming} streamText={streamText}
          ownerName="销冠"
          placeholder="输入客户问题..."
          footer={
            <div className="flex gap-2">
              {sourceData && mode === 'with_skill' && (
                <details className="text-xs text-muted-foreground-2 cursor-pointer">
                  <summary>📋 技能溯源</summary>
                  <div className="mt-1 space-y-1 bg-surface rounded p-2 border">
                    <p>来源：{sourceData.materialFileName || '未知'}</p>
                    {sourceData.sourceExcerpt && <p>"{sourceData.sourceExcerpt}"</p>}
                    {sourceData.qualityScore != null && <p>质量评分：⭐ {sourceData.qualityScore.toFixed(1)}</p>}
                  </div>
                </details>
              )}
              <button onClick={handleNewRound} disabled={streaming}
                className="px-3 py-1.5 border rounded-lg text-xs text-muted-foreground hover:text-foreground disabled:opacity-30">下一轮</button>
            </div>
          }
        >
          {messages.map((m, i) => (
            <div key={i}>
              <div className={`flex ${m.role === 'customer' ? 'justify-start' : 'justify-end'}`}>
                <div className={`max-w-[75%] rounded-xl px-4 py-2.5 text-sm ${
                  m.role === 'customer' ? 'bg-primary-light text-gray-800' : 'bg-green-500 text-white'
                }`}>
                  <p className="text-xs text-green-200 mb-1">{m.role === 'customer' ? '客户' : '销售'}</p>
                  {m.content}
                </div>
              </div>
              {m.analysis && (
                <div className="mt-1 mx-4 p-2 rounded text-xs bg-green-50 text-green-800 border border-green-100">
                  <b>{m.skillMode === 'without_skill' ? '⚠️ 不足' : '✅ 为什么有效'}：</b>{m.analysis}
                </div>
              )}
            </div>
          ))}
          {streamText && (
            <div className="flex justify-end">
              <div className="max-w-[75%] rounded-xl px-4 py-2.5 text-sm bg-green-500 text-white">
                <p className="text-xs text-green-200 mb-1">回复中...</p>
                {streamText}
              </div>
            </div>
          )}
          {streamAnalysis && (
            <div className="mx-4 p-2 rounded text-xs bg-green-50 text-green-800 border border-green-100">
              <b>{mode === 'with_skill' ? '✅ 为什么有效' : '⚠️ 不足'}：</b>{streamAnalysis}
            </div>
          )}
        </SkillChatView>
      </div>
    </div>
  );
}
