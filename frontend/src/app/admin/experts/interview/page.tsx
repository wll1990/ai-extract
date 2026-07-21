'use client';
import React, { useState, useEffect, useCallback } from 'react';
import { useRouter } from 'next/navigation';
import { getSpaces } from '@/lib/api/spaces';
import { getAvailableExperts, getExperts } from '@/lib/api/expert';
import { createInterview, getActiveSessions, type ActiveSessionItem } from '@/lib/api/interview';

const EXPERT_TOPICS = [
  '如何快速判断一个销冠属于哪种类型',
  '追问的时机：什么时候该追问，什么时候该放过',
  '萃取失败案例复盘：那次为什么没挖到核心',
  '如何从销冠的微表情和身体语言中捕捉信号',
  '新手萃取师最常见的三个错误',
  '如何验证自己真的挖到了核心经验',
];

interface ExpertOption { id: string; name: string; styleTags?: string[]; industryTags?: string[]; type: 'composite'|'single'|'none'; }

export default function ExpertInterviewPage() {
  const router = useRouter();
  const [selectedTopic, setSelectedTopic] = useState('');
  const [customTopic, setCustomTopic] = useState('');
  const [isCustom, setIsCustom] = useState(false);
  const [loading, setLoading] = useState(false);
  const [activeSession, setActiveSession] = useState<ActiveSessionItem | null>(null);
  const [spaceId, setSpaceId] = useState('');
  const [selectedExpert, setSelectedExpert] = useState<ExpertOption>({ id:'', name:'综合（默认）', type:'composite' });
  const [expertOptions, setExpertOptions] = useState<ExpertOption[]>([]);
  const [showExpertDropdown, setShowExpertDropdown] = useState(false);

  // 访谈对象选择
  const [intervieweeId, setIntervieweeId] = useState('');
  const [intervieweeName, setIntervieweeName] = useState('');
  const [isNewInterviewee, setIsNewInterviewee] = useState(false);
  const [expertList, setExpertList] = useState<any[]>([]);

  useEffect(() => {
    getSpaces(undefined, undefined, 1, 1).then(d => {
      if (d.content?.length > 0) setSpaceId(d.content[0].id);
    }).catch(() => {});
    getActiveSessions().then(data => {
      if (data.hasActive && data.sessions.length > 0) setActiveSession(data.sessions[0]);
    }).catch(() => {});
    // 加载萃取风格
    getAvailableExperts().then(data => {
      const list = data.map((e: any) => ({ id:e.id||'', name:e.name, styleTags:e.styleTags, industryTags:e.industryTags, type:e.type }));
      setExpertOptions(list);
      const composite = list.find((e: ExpertOption) => e.type === 'composite');
      if (composite) setSelectedExpert(composite);
    }).catch(() => {});
    // 加载已有萃取师列表（作为访谈对象）
    getExperts(1, 50).then((d: any) => setExpertList(d.content || [])).catch(() => {});
  }, []);

  const getTopic = useCallback(() => isCustom ? customTopic.trim() : selectedTopic, [isCustom, customTopic, selectedTopic]);

  const handleStart = useCallback(async () => {
    const topic = getTopic(); if (!topic || !spaceId) return;
    setLoading(true);
    try {
      const expertSkillId = selectedExpert.type === 'composite' ? undefined : selectedExpert.id;
      const session = await createInterview({ spaceId, topic, expertSkillId, interviewType: 'expert' });
      router.push(`/interview/${session.sessionId}`);
    } catch (err) { console.error('创建失败:', err); }
    finally { setLoading(false); }
  }, [getTopic, spaceId, selectedExpert, router]);

  return (
    <div className="min-h-screen bg-surface">
      {activeSession && (
        <div className="sticky top-0 z-40 flex items-center justify-between bg-warning-bg px-6 py-3">
          <span className="text-sm text-warning">你有进行中的访谈「{activeSession.topic}」</span>
          <button onClick={() => router.push(`/interview/${activeSession.sessionId}`)}
            className="rounded-md bg-warning px-4 py-1.5 text-sm font-medium text-white">继续</button>
        </div>
      )}
      <div className="mx-auto max-w-[640px] px-6 pb-12 pt-8">
        <button onClick={() => router.back()} className="text-xs text-muted-foreground-2 hover:text-muted-foreground mb-4">← 返回萃取师经验库</button>
        <h1 className="mb-2 text-2xl font-bold text-foreground">萃取师访谈</h1>
        <p className="mb-8 text-sm text-muted-foreground">通过元萃取引擎，深度挖掘萃取师本人的实践智慧和判断直觉</p>

        {/* 选择访谈对象 */}
        <div className="mb-6 card">
          <h2 className="text-lg font-semibold text-foreground mb-4">👤 选择访谈对象</h2>
          <label className="flex items-center gap-2 text-sm mb-3">
            <input type="checkbox" checked={isNewInterviewee} onChange={e => setIsNewInterviewee(e.target.checked)} className="accent-navy" />
            新建萃取师
          </label>
          {isNewInterviewee ? (
            <input value={intervieweeName} onChange={e => setIntervieweeName(e.target.value)}
              placeholder="输入萃取师姓名" className="w-full border border-border rounded-md p-2 text-sm" />
          ) : (
            <select value={intervieweeId} onChange={e => setIntervieweeId(e.target.value)}
              className="w-full border border-border rounded-md p-2 text-sm">
              <option value="">-- 选择已有萃取师（可选） --</option>
              {expertList.map((e: any) => (
                <option key={e.id} value={e.id}>{e.name} · {e.grainCount || 0}条颗粒</option>
              ))}
            </select>
          )}
        </div>

        {/* 萃取风格选择 */}
        <div className="mb-6 card">
          <h2 className="text-lg font-semibold text-foreground mb-4">🧠 选择萃取风格</h2>
          <div className="relative">
            <button onClick={() => setShowExpertDropdown(!showExpertDropdown)}
              className="w-full flex items-center justify-between rounded-md border border-border px-4 py-3 text-sm hover:border-primary">
              <span className="text-foreground">{selectedExpert.name}</span>
              <span className="text-muted-foreground-2">{showExpertDropdown ? '▲' : '▼'}</span>
            </button>
            {showExpertDropdown && (
              <div className="absolute z-10 mt-1 w-full rounded-lg border border-border bg-surface-2 shadow-md max-h-64 overflow-y-auto">
                {expertOptions.map(expert => (
                  <button key={expert.id || 'composite'} onClick={() => { setSelectedExpert(expert); setShowExpertDropdown(false); }}
                    className="w-full px-4 py-3 text-left hover:bg-primary-light">
                    <div className="text-sm text-foreground">{expert.name}</div>
                    {expert.styleTags && expert.industryTags && (
                      <div className="mt-1 flex flex-wrap gap-1">
                        {expert.styleTags.map((t: string) => <span key={t} className="rounded-full bg-primary-light px-2 py-0.5 text-[11px] text-primary">{t}</span>)}
                        {expert.industryTags.map((t: string) => <span key={t} className="rounded-full bg-primary-light px-2 py-0.5 text-[11px] text-muted-foreground">{t}</span>)}
                      </div>
                    )}
                  </button>
                ))}
              </div>
            )}
          </div>
          {selectedExpert.type === 'none' && <p className="mt-2 text-xs text-warning">⚠️ 基础版不包含真实萃取师经验，追问可能不够深入</p>}
        </div>

        {/* 主题选择 */}
        <div className="mb-6 card">
          <h2 className="text-lg font-semibold text-foreground mb-4">📋 选择访谈主题</h2>
          <div className="space-y-2">
            {EXPERT_TOPICS.map(topic => (
              <label key={topic} className={`flex cursor-pointer items-center gap-3 rounded-lg border px-4 py-3 transition-all ${selectedTopic === topic && !isCustom ? 'border-foreground bg-primary-light' : 'border-border hover:border-border-strong'}`}>
                <input type="radio" name="topic" checked={selectedTopic === topic && !isCustom}
                  onChange={() => { setSelectedTopic(topic); setIsCustom(false); }} className="h-4 w-4 accent-navy" />
                <span className="text-sm text-foreground">{topic}</span>
              </label>
            ))}
            {/* 自定义主题 */}
            <label className={`flex cursor-pointer items-center gap-3 rounded-lg border px-4 py-3 transition-all ${isCustom ? 'border-foreground bg-primary-light' : 'border-border hover:border-border-strong'}`}>
              <input type="radio" name="topic" checked={isCustom}
                onChange={() => setIsCustom(true)} className="h-4 w-4 accent-navy" />
              <span className="text-sm text-foreground">自定义主题</span>
            </label>
            {isCustom && (
              <input value={customTopic} onChange={e => setCustomTopic(e.target.value)}
                placeholder="输入你想聊的话题..."
                className="w-full border border-border rounded-lg p-2.5 text-sm" />
            )}
          </div>
        </div>

        {/* 操作 */}
        <div className="flex items-center justify-between">
          <button onClick={() => router.back()} className="btn btn-secondary">取消</button>
          <button onClick={handleStart} disabled={!getTopic() || loading}
            className="btn btn-primary px-8 py-3">{loading ? '创建中...' : '开始萃取师访谈'}</button>
        </div>
      </div>
    </div>
  );
}
