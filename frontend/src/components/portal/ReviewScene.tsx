'use client';

import { useEffect, useRef, useState } from 'react';
import { Check, CircleNotch, Quotes, Sparkle } from '@phosphor-icons/react';
import { StatusPill } from './primitives';

interface Segment {
  seq: number;
  speaker: '客户' | '销售';
  text: string;
}

interface Block {
  key: string;
  title: string;
  point: string;
  seg: number;
  tone: 'green' | 'orange' | 'red' | 'purple';
  cite: string;
}

const SEGMENTS: Segment[] = [
  { seq: 1, speaker: '客户', text: '我们内部还在评估，暂时不着急上新的系统。' },
  { seq: 2, speaker: '销售', text: '理解，张总。我不催您做决定。如果今年不上，最让您担心的是什么？' },
  { seq: 3, speaker: '客户', text: '主要是怕上线之后影响业务，去年我们换过一次，出了问题。' },
  { seq: 4, speaker: '销售', text: '去年那次是哪一段出的问题？是数据迁移还是切换窗口？' },
  { seq: 5, speaker: '客户', text: '切换窗口。业务部门停了半天。' },
  { seq: 6, speaker: '销售', text: '我们可以在周末切换，并保留旧系统并行两周，异常一键回退。' },
];

const BLOCKS: Block[] = [
  { key: 'good', title: '做得好', point: '先确认顾虑，而不是催单', seg: 2, tone: 'green', cite: '依据 1' },
  { key: 'weak', title: '需改进', point: '未确认预算归属与最终签字人', seg: 6, tone: 'orange', cite: '依据 1' },
  { key: 'risk', title: '风险', point: '客户有过失败切换经历，信任需要证据', seg: 3, tone: 'red', cite: '依据 2' },
  { key: 'signal', title: '客户信号', point: '担心切换影响业务（客户原话）', seg: 3, tone: 'purple', cite: '原话' },
  { key: 'next', title: '下一步', point: '发送并行回退说明，约三方对齐', seg: 6, tone: 'purple', cite: '依据 1' },
];

const PHASES = ['切分对话', '检索经验', '生成五个部分', '校验引用'];

const TICK_MS = 520;
const SEG_END = SEGMENTS.length;           // 6
const BLOCK_START = SEG_END + 2;            // 8
const BLOCK_END = BLOCK_START + BLOCKS.length; // 13
const TOTAL = BLOCK_END + 2;                // 15

export function ReviewScene({ playing }: { playing: boolean }) {
  const [tick, setTick] = useState(0);
  const [hovered, setHovered] = useState<number | null>(null);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    if (!playing) {
      setTick(0);
      return;
    }
    // 开启「减少动态效果」时直接给出最终结果，不做逐条推进
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
      setTick(TOTAL);
      return;
    }
    setTick(0);
    timer.current = setInterval(() => {
      setTick(prev => {
        if (prev >= TOTAL) {
          if (timer.current) clearInterval(timer.current);
          return prev;
        }
        return prev + 1;
      });
    }, TICK_MS);
    return () => {
      if (timer.current) clearInterval(timer.current);
    };
  }, [playing]);

  const visibleSegments = Math.min(SEGMENTS.length, tick);
  const visibleBlocks = tick < BLOCK_START ? 0 : Math.min(BLOCKS.length, tick - BLOCK_START);
  const phaseIndex = tick < SEG_END ? 0 : tick < BLOCK_START ? 1 : tick < BLOCK_END ? 2 : 3;
  const activeBlock = visibleBlocks > 0 ? BLOCKS[visibleBlocks - 1] : null;
  const verified = tick >= BLOCK_END - 1;
  const highlightedSeg = hovered !== null ? BLOCKS[hovered]?.seg : activeBlock?.seg;

  return (
    <div className="demo-stage">
      <div className="demo-chat">
        <div className="demo-mode">
          <div>
            <span className="demo-scene-label">复盘一次客户沟通</span>
          </div>
          <StatusPill tone="gray">6 段对话 · 5 个结论</StatusPill>
        </div>

        <div className="review-scene">
          <div className="rd-transcript">
            {SEGMENTS.map(seg => {
              const visible = seg.seq <= visibleSegments;
              const highlighted = highlightedSeg === seg.seq;
              return (
                <div
                  key={seg.seq}
                  className={`rd-seg ${seg.speaker === '销售' ? 'sales' : 'customer'} ${visible ? 'in' : ''} ${highlighted ? 'hit' : ''}`}
                >
                  <span>{seg.speaker}</span>
                  <p>{seg.text}</p>
                </div>
              );
            })}
          </div>

          <div className="rd-blocks">
            {BLOCKS.map((b, i) => (
              <article
                key={b.key}
                className={`rd-block ${i < visibleBlocks ? 'in' : ''}`}
                tabIndex={0}
                onMouseEnter={() => setHovered(i)}
                onMouseLeave={() => setHovered(null)}
                onFocus={() => setHovered(i)}
                onBlur={() => setHovered(null)}
              >
                <header>
                  <StatusPill tone={b.tone}>{b.title}</StatusPill>
                  <span className={`rd-cite ${verified && i < visibleBlocks ? 'ok' : ''}`}>
                    <Quotes />{b.cite}
                  </span>
                </header>
                <p>{b.point}</p>
              </article>
            ))}
          </div>
        </div>
      </div>

      <aside className="demo-rail">
        <div className="rail-title">
          <span>复盘过程</span>
          <StatusPill tone={tick >= TOTAL ? 'green' : 'orange'}>
            {tick >= TOTAL ? '已完成' : '进行中'}
          </StatusPill>
        </div>
        <ol className="plan-list">
          {PHASES.map((phase, i) => {
            const done = i < phaseIndex || tick >= TOTAL;
            const running = i === phaseIndex && tick < TOTAL;
            return (
              <li key={phase} className={done ? 'done' : running ? 'running' : ''}>
                {done ? <Check /> : running ? <CircleNotch /> : <span>{i + 1}</span>}
                {phase}
              </li>
            );
          })}
        </ol>

        <div className="rail-callout">
          <Sparkle />
          <div>
            <b>每条结论都能回到原话</b>
            <p>鼠标移到一个结论上，左侧会亮起它在对话里的出处。</p>
          </div>
        </div>
      </aside>
    </div>
  );
}
