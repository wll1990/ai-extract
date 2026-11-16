'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ArrowRight, Check, CircleNotch, FileText, ListChecks, Quotes, ShieldCheck, Sparkle, Warning,
} from '@phosphor-icons/react';
import { chat, listSkills } from '@/lib/api/skill';
import { track } from '@/lib/analytics';
import { StatusPill } from './primitives';
import { ReviewScene } from './ReviewScene';
import { PracticeScene } from './PracticeScene';
import { CoachScene } from './CoachScene';

interface Scenario {
  key: string;
  label: string;
  basis: string;
  question: string;
  chunks: string[];
  sources: Array<{ label: string; detail: string }>;
  boundaryQuestion: string;
  boundaryAnswer: string;
}

/** 三个行业场景。同一个销冠的经验，在不同客户类型上的问法不一样。 */
const SCENARIOS: Record<string, Scenario> = {
  saas: {
    key: 'saas',
    label: '软件 / SaaS',
    basis: '基于 3 条已确认经验',
    question: '关键人支持我们的方案，但采购已经拖了三周。我该继续催采购，还是先找业务负责人？',
    chunks: [
      '先不要把“采购拖延”当成采购部门的问题。',
      '更常见的情况是，业务负责人还没有完成内部优先级确认，采购因此没有启动条件。',
      '建议先验证两个信号：',
      '一，业务负责人能否说清“不做这件事”的损失；',
      '二，除了你的关键人，是否还有预算所有者愿意为项目背书。',
      '下一步：请关键人安排一次 20 分钟的三方对齐，目标是确认业务影响、预算归属和采购启动条件。',
    ],
    sources: [
      { label: '经历依据 2', detail: '客户认可价值但采购不启动 · 相似度 86%' },
      { label: '适用边界', detail: '决策链长、采购流程规范的场景' },
      { label: '判断来源', detail: '3 条已确认经验' },
    ],
    boundaryQuestion: '如果客户今年的预算已经冻结，这套方法还能用吗？',
    boundaryAnswer:
      '这条超出我目前有依据的范围。预算冻结的情况下，我建议直接找陈锋本人确认，避免给你一个没有依据的答案。',
  },
  manufacturing: {
    key: 'manufacturing',
    label: '制造业设备',
    basis: '基于 4 条已确认经验',
    question: '产线负责人认可设备，但采购说要走年度集采，今年不单独立项。还有机会吗？',
    chunks: [
      '有机会，但打法要换：年度集采不是“不买”，是“排期”。',
      '先分清楚一件事：客户是没钱，还是没有可用的预算科目。',
      '如果是没科目，唯一能推动立项的弹药是停机损失——这是产线负责人自己算得出来的数字。',
      '把“今年不立项”翻译成具体金额和停线天数，立项才有理由走特批。',
      '同时问清楚集采的启动窗口，倒排资料准备时间，别等到窗口关了才提交。',
      '下一步：本周约产线负责人做一次 30 分钟的停机损失核算，产出一个数字，再谈立项。',
    ],
    sources: [
      { label: '经历依据 3', detail: '设备采购进入年度集采后的翻盘 · 相似度 89%' },
      { label: '适用边界', detail: '产线停机成本可量化、采购走集采流程' },
      { label: '判断来源', detail: '4 条已确认经验' },
    ],
    boundaryQuestion: '如果客户这条产线今年就要停产改造，还值得投入吗？',
    boundaryAnswer:
      '这条我没有直接经历，不给你结论。产线停产改造的判断涉及资产处置，建议找陈锋本人确认。',
  },
  finance: {
    key: 'finance',
    label: '金融对公',
    basis: '基于 5 条已确认经验',
    question: '资金经理认可我们的策略，但风控一直不批，理由只有一句“看不懂”。这种怎么推？',
    chunks: [
      '“看不懂”通常不是理解问题，是责任问题：批了出问题，谁担。',
      '第一步是把风控从否决者变成共同作者：请他们提三条必须写进材料的条件。',
      '材料改法：把结论页换成“风险敞口、最坏情形、止损触发条件”一页。',
      '同时向资金经理确认一件事：如果风控提的条件都满足，他会不会签字。',
      '如果他说不会，说明真正的阻力不在风控，别在错的人身上耗时间。',
      '下一步：本周约风控做一次 20 分钟的条件对齐，把口头理由变成书面条件。',
    ],
    sources: [
      { label: '经历依据 1', detail: '风控以“看不懂”为由卡住的产品准入 · 相似度 91%' },
      { label: '适用边界', detail: '机构客户、有独立风控部门的场景' },
      { label: '判断来源', detail: '5 条已确认经验' },
    ],
    boundaryQuestion: '如果客户是自营资金，没有独立风控，这套还能用吗？',
    boundaryAnswer:
      '这条超出我目前有依据的范围。自营资金的决策链完全不同，建议找陈锋本人确认后再行动。',
  },
};

type SceneKey = 'chat' | 'practice' | 'review' | 'coach' | 'convert';

const SCENE_TABS: Array<{ key: SceneKey; label: string }> = [
  { key: 'chat', label: '问销冠' },
  { key: 'practice', label: '实战对练' },
  { key: 'review', label: '复盘' },
  { key: 'coach', label: '补充与认证' },
  { key: 'convert', label: '转下一步' },
];

type Mode = 'replay' | 'live';
type Phase = 'idle' | 'playing' | 'done' | 'error';

export function DemoPlayer({ loggedIn }: { loggedIn: boolean }) {
  const [scene, setScene] = useState<SceneKey>('chat');
  const [scenarioKey, setScenarioKey] = useState<string>('saas');
  const [mode, setMode] = useState<Mode>('replay');
  const [phase, setPhase] = useState<Phase>('idle');
  const [text, setText] = useState('');
  const [sources, setSources] = useState<Array<{ label: string; detail: string }>>([]);
  const [showApproval, setShowApproval] = useState(false);
  const [stage, setStage] = useState(0); // 0 无 / 1 越界 / 2 已转化
  const [error, setError] = useState<string | null>(null);

  const rootRef = useRef<HTMLDivElement>(null);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);
  const stageTimers = useRef<Array<ReturnType<typeof setTimeout>>>([]);
  const abort = useRef<AbortController | null>(null);
  const skillId = useRef<string | null>(null);
  const playedRef = useRef(false);
  const scenarioRef = useRef<Scenario>(SCENARIOS.saas);
  scenarioRef.current = SCENARIOS[scenarioKey];

  const clearTimers = () => {
    if (timer.current) {
      clearInterval(timer.current);
      timer.current = null;
    }
    stageTimers.current.forEach(clearTimeout);
    stageTimers.current = [];
  };

  const stop = useCallback(() => {
    clearTimers();
    abort.current?.abort();
    abort.current = null;
  }, []);

  useEffect(() => () => stop(), [stop]);

  const playReplay = useCallback((s: Scenario = scenarioRef.current) => {
    stop();
    setMode('replay');
    setPhase('playing');
    setText('');
    setSources([]);
    setShowApproval(false);
    setStage(0);
    setError(null);
    track('demo_play', { mode: 'replay', scenario: s.key });

    // 开启「减少动态效果」时不逐字播放，直接给出完整结果
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
      setText(s.chunks.join(''));
      setSources(s.sources);
      setPhase('done');
      setShowApproval(true);
      setStage(2);
      return;
    }

    let i = 0;
    timer.current = setInterval(() => {
      i += 1;
      setText(s.chunks.slice(0, i).join(''));
      if (i >= s.chunks.length) {
        clearTimers();
        setSources(s.sources);
        setPhase('done');
        track('demo_complete', { mode: 'replay', scenario: s.key });
        stageTimers.current.push(
          setTimeout(() => setShowApproval(true), 400),
          setTimeout(() => setStage(1), 1200),
          setTimeout(() => setStage(2), 2600),
        );
      }
    }, 520);
  }, [stop]);

  const runLive = useCallback(async () => {
    stop();
    setMode('live');
    setPhase('playing');
    setText('');
    setSources([]);
    setShowApproval(false);
    setStage(0);
    setError(null);
    track('demo_live', { scenario: scenarioRef.current.key });

    try {
      if (!skillId.current) {
        const page = await listSkills(1, 1, 'published');
        skillId.current = page.content[0]?.id ?? null;
      }
      if (!skillId.current) {
        setError('当前没有已发布的分身，无法真实生成。');
        setPhase('error');
        return;
      }
      abort.current = chat(skillId.current, scenarioRef.current.question, {
        onChunk: chunk => setText(prev => prev + chunk),
        onSource: (_reportId, reportTitle, _grainIds, grainTags, grainCount, avgScore) => {
          setSources(prev => [
            ...prev,
            {
              label: grainTags || reportTitle || '经验来源',
              detail: [grainCount ? `${grainCount} 条经验` : '', avgScore ? `相似度 ${avgScore}` : '']
                .filter(Boolean).join(' · ') || '来自已确认经验',
            },
          ]);
        },
        onKnowledgeSource: (title, source, similarity) => {
          setSources(prev => [...prev, { label: title, detail: `${source} · ${similarity}` }]);
        },
        onCaseSource: (title, source, similarity) => {
          setSources(prev => [...prev, { label: title, detail: `${source} · ${similarity}` }]);
        },
        onDone: () => {
          setPhase('done');
          track('demo_complete', { mode: 'live', scenario: scenarioRef.current.key });
          stageTimers.current.push(setTimeout(() => setShowApproval(true), 300));
        },
        onError: message => {
          setError(message || '生成失败，请稍后重试。');
          setPhase('error');
        },
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : '生成失败，请稍后重试。');
      setPhase('error');
    }
  }, [stop]);

  // 首次进入视口自动播放回放
  useEffect(() => {
    const el = rootRef.current;
    if (!el || typeof IntersectionObserver === 'undefined') {
      if (!playedRef.current) { playedRef.current = true; playReplay(); }
      return;
    }
    const io = new IntersectionObserver(
      entries => {
        if (entries.some(e => e.isIntersecting) && !playedRef.current) {
          playedRef.current = true;
          io.disconnect();
          // 外面这层 Reveal 有 0.5s 淡入，等它结束再开播，
          // 否则用户是在半透明的卡片里看开头两句
          stageTimers.current.push(setTimeout(() => playReplay(), 620));
        }
      },
      { threshold: 0.2 },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [playReplay]);

  const switchMode = (next: Mode) => {
    if (next === 'live') {
      if (!loggedIn) return;
      void runLive();
    } else {
      playReplay();
    }
  };

  const switchScenario = (key: string) => {
    if (key === scenarioKey) return;
    const next = SCENARIOS[key];
    scenarioRef.current = next;
    setScenarioKey(key);
    playedRef.current = true;
    track('demo_scenario', { scenario: key });
    if (mode === 'live') {
      void runLive();
    } else {
      playReplay(next);
    }
  };

  const isReplay = mode === 'replay';
  const scenario = SCENARIOS[scenarioKey];

  return (
    <div ref={rootRef}>
      <div className="demo-tabs">
        {SCENE_TABS.map((tab, i) => (
          <button
            key={tab.key}
            type="button"
            className={scene === tab.key ? 'active' : ''}
            onClick={() => setScene(tab.key)}
          >
            <i aria-hidden>{i + 1}</i>
            {tab.label}
          </button>
        ))}
      </div>

      {scene === 'practice' && <PracticeScene playing />}
      {scene === 'review' && <ReviewScene playing />}
      {scene === 'coach' && <CoachScene playing />}

      {scene === 'convert' && (
        <div className="demo-stage">
          <div className="demo-chat">
            <div className="demo-mode">
              <div><span className="demo-scene-label">把建议变成下一步</span></div>
              <StatusPill tone="gray">2 个动作 · 均需确认</StatusPill>
            </div>
            <div className="demo-thread">
              <div className="demo-convert">
                <div className="demo-convert-item">
                  <Check weight="bold" />
                  <div>
                    <b>已转为线索</b>
                    <small>华星科技 · 跟进中</small>
                  </div>
                </div>
                <div className="demo-convert-item">
                  <Check weight="bold" />
                  <div>
                    <b>已创建任务</b>
                    <small>发送并行回退说明 · 明天 18:00</small>
                  </div>
                </div>
              </div>
              <div className="demo-approval">
                <div className="approval-icon"><ListChecks /></div>
                <div>
                  <span className="eyebrow">执行前确认</span>
                  <h3>准备生成「三方对齐会提纲」</h3>
                  <p>将使用本次对话与客户背景，不会发送给任何外部联系人。</p>
                  <div className="approval-scope">
                    <span><FileText />读取 1 份资料</span>
                    <span><ShieldCheck />不写入长期记忆</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
          <aside className="demo-rail">
            <div className="rail-title">
              <span>执行轨迹</span>
              <StatusPill tone="green">已确认</StatusPill>
            </div>
            <ol className="plan-list">
              <li className="done"><Check />理解目标与限制</li>
              <li className="done"><Check />匹配经验与边界</li>
              <li className="done"><Check />形成建议与依据</li>
              <li className="done"><Check />等待你的确认</li>
            </ol>
            <div className="rail-callout">
              <ShieldCheck />
              <div>
                <b>关键动作由你确认</b>
                <p>转线索、建任务、发送外部消息，都会先展示内容再执行。</p>
              </div>
            </div>
          </aside>
        </div>
      )}

      {scene === 'chat' && (
        <div className="demo-stage">
      <div className="demo-chat">
        <div className="demo-mode">
          <div>
            <button type="button" className={mode === 'replay' ? 'active' : ''} onClick={() => switchMode('replay')}>
              演示回放
            </button>
            <button
              type="button"
              className={mode === 'live' ? 'active' : ''}
              onClick={() => switchMode('live')}
              disabled={!loggedIn}
              title={loggedIn ? '调用真实后端 SSE' : '登录后可切换真实生成'}
            >
              真实生成
            </button>
          </div>
          <StatusPill tone={mode === 'live' ? 'green' : 'gray'}>
            {mode === 'live' ? '真实后端 · 现场生成' : '示例对话'}
          </StatusPill>
        </div>

        <div className="scenario-switch">
          <span>换个行业看：</span>
          {Object.values(SCENARIOS).map(s => (
            <button
              key={s.key}
              type="button"
              className={scenarioKey === s.key ? 'active' : ''}
              onClick={() => switchScenario(s.key)}
            >
              {s.label}
            </button>
          ))}
        </div>

        <div className="demo-thread">
          <div className="message user-message">
            <p>{scenario.question}</p>
          </div>

          <div className="message expert-message">
            <div className="message-source">
              <div className="agent-orb"><Sparkle weight="fill" /></div>
              <span>
                <b>陈锋的分身</b>
                <small>{mode === 'live' ? '基于本次检索结果' : scenario.basis}</small>
              </span>
              <StatusPill tone={phase === 'playing' ? 'orange' : 'green'}>
                {phase === 'playing' ? '生成中' : phase === 'error' ? '失败' : '依据充分'}
              </StatusPill>
            </div>

            <p>
              {text}
              {phase === 'playing' && <span className="demo-caret" aria-hidden />}
            </p>

            {error && <p style={{ color: 'var(--red)' }}>{error}</p>}

            {sources.length > 0 && (
              <div className="citation-row">
                {sources.slice(0, 3).map((s, i) => (
                  <span key={`${s.label}-${i}`} className="demo-citation" title={s.detail}>
                    {i === 0 ? <Quotes /> : <FileText />}
                    {s.label}
                  </span>
                ))}
              </div>
            )}
          </div>

          {isReplay && stage >= 1 && (
            <>
              <div className="message user-message">
                <p>{scenario.boundaryQuestion}</p>
              </div>
              <div className="message expert-message demo-boundary">
                <div className="message-source">
                  <div className="agent-orb"><Sparkle weight="fill" /></div>
                  <span>
                    <b>陈锋的分身</b>
                    <small>这条没有已确认依据</small>
                  </span>
                  <StatusPill tone="orange">超出范围</StatusPill>
                </div>
                <p>{scenario.boundaryAnswer}</p>
              </div>
            </>
          )}

          {isReplay && stage >= 2 && (
            <div className="demo-convert">
              <div className="demo-convert-item">
                <Check weight="bold" />
                <div>
                  <b>已转为线索</b>
                  <small>华星科技 · 跟进中</small>
                </div>
              </div>
              <div className="demo-convert-item">
                <Check weight="bold" />
                <div>
                  <b>已创建任务</b>
                  <small>发送并行回退说明 · 明天 18:00</small>
                </div>
              </div>
            </div>
          )}
        </div>

        <div className="demo-controls">
          <button type="button" className="button button-secondary button-small" onClick={() => playReplay()}>
            重播演示
          </button>
          {!loggedIn && (
            <a className="text-link" href="/login?redirect=/portal">
              登录后可切换真实生成 <ArrowRight />
            </a>
          )}
        </div>
      </div>

      <aside className="demo-rail">
        <div className="rail-title">
          <span>执行轨迹</span>
          <StatusPill tone={phase === 'playing' ? 'orange' : 'gray'}>
            {phase === 'playing' ? '执行中' : '演示'}
          </StatusPill>
        </div>
        <ol className="plan-list">
          <li className="done"><Check />理解目标与限制</li>
          <li className={phase === 'playing' ? 'running' : phase === 'done' ? 'done' : ''}>
            {phase === 'playing' ? <CircleNotch /> : phase === 'done' ? <Check /> : <span>2</span>}
            匹配经验与边界
          </li>
          <li className={phase === 'done' ? 'done' : ''}>
            {phase === 'done' ? <Check /> : <span>3</span>}形成建议与依据
          </li>
          <li className={stage >= 2 ? 'done' : ''}>
            {stage >= 2 ? <Check /> : <span>4</span>}等待你的确认
          </li>
        </ol>

        <div className="rail-callout">
          <ShieldCheck />
          <div>
            <b>你始终拥有控制权</b>
            <p>转线索、建任务、发送外部消息前，系统都会先展示内容并等你确认。</p>
          </div>
        </div>

        {showApproval && (
          <div className="demo-approval">
            <div className="approval-icon"><ListChecks /></div>
            <div>
              <span className="eyebrow">执行前确认</span>
              <h3>准备生成「三方对齐会提纲」</h3>
              <p>将使用本次对话与客户背景，不会发送给任何外部联系人。</p>
              <div className="approval-scope">
                <span><FileText />读取 1 份资料</span>
                <span><ShieldCheck />不写入长期记忆</span>
              </div>
            </div>
          </div>
        )}

        {isReplay && stage >= 1 && (
          <div className="demo-rail-note">
            <Warning />
            <p>超出经验范围时，分身会明确说“不知道”，并建议找本人确认。</p>
          </div>
        )}
      </aside>
        </div>
      )}
    </div>
  );
}
