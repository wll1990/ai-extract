'use client';

import { useEffect, useRef, useState } from 'react';
import { Check, CircleNotch, Flag, Target, Warning } from '@phosphor-icons/react';
import { StatusPill } from './primitives';

interface Turn {
  sales: string;
  customer: string;
  score: number;
  verdict: 'good' | 'ok' | 'bad';
  note: string;
}

const SETUP = {
  role: '采购总监 · 王总',
  style: '重技术、谨慎、习惯先压价',
  goal: '拿到一次方案评审机会',
};

const TURNS: Turn[] = [
  {
    sales: '王总，上次您提的换型后前两批不稳定，我想先确认现在卡在哪一步。',
    customer: '还是老问题，换型之后前两批良率上不去。',
    score: 7.8,
    verdict: 'good',
    note: '开场直接接上次没解决的事，客户愿意继续说。',
  },
  {
    sales: '如果换型停机能从 6 小时压到 2 小时以内，对您这条线的排产影响有多大？',
    customer: '那就不一样了，我们现在每个月要停两条线。',
    score: 8.6,
    verdict: 'good',
    note: '用具体数字把价值锚定到客户的排产上，客户自己说出了损失。',
  },
  {
    sales: '我们现在有个活动，这周下单可以打八折。',
    customer: '……价格先不急，你先把技术方案发我看看。',
    score: 4.2,
    verdict: 'bad',
    note: '客户已经在谈价值和方案，此时转价格，把话题拉回低层。',
  },
];

/**
 * 四个维度取自对练评判 Prompt（prompts/org_skill_practice_evaluate.md），
 * 不是这里自拟的：策略正确性 / 话术说服力 / 客户洞察 / 话术复用，每项 10 分制。
 */
const DIMENSIONS: Array<{ name: string; scores: number[] }> = [
  { name: '策略正确性', scores: [7.2, 7.2, 7.2] },
  { name: '话术说服力', scores: [6.0, 8.6, 8.6] },
  { name: '客户洞察', scores: [0, 0, 4.5] },
  { name: '话术复用', scores: [0, 0, 3.0] },
];

const STEP_MS = 1100;
const TOTAL = TURNS.length;

export function PracticeScene({ playing }: { playing: boolean }) {
  const [tick, setTick] = useState(0);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    if (!playing) {
      setTick(0);
      return;
    }
    // 开启「减少动态效果」时直接给出最终结果，不做逐轮推进
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
    }, STEP_MS);
    return () => {
      if (timer.current) clearInterval(timer.current);
    };
  }, [playing]);

  const done = tick >= TOTAL;
  const scoreOf = (i: number) => DIMENSIONS[i].scores[Math.max(0, Math.min(tick, TOTAL) - 1)];
  const final = done
    ? (TURNS.reduce((sum, t) => sum + t.score, 0) / TURNS.length).toFixed(1)
    : null;

  return (
    <div className="demo-stage">
      <div className="demo-chat">
        <div className="demo-mode">
          <div>
            <span className="demo-scene-label">和 AI 扮演的客户对练一局</span>
          </div>
          <StatusPill tone="gray">一局 3 轮 · 逐轮评判</StatusPill>
        </div>

        <div className="practice-scene">
          <div className="pr-setup">
            <Target />
            <div>
              <b>{SETUP.role}</b>
              <small>{SETUP.style}</small>
            </div>
            <StatusPill tone="orange">本局目标：{SETUP.goal}</StatusPill>
          </div>

          {TURNS.map((turn, i) => {
            const visible = i < tick;
            const judging = i === tick - 1 && !done && i === TURNS.length - 1;
            return (
              <div key={turn.sales} className={`pr-turn ${visible ? 'in' : ''}`}>
                <div className="pr-sales">
                  <span>你</span>
                  <p>{turn.sales}</p>
                </div>
                <div className="pr-customer">
                  <span>{SETUP.role.split(' · ')[1]}</span>
                  <p>{turn.customer}</p>
                </div>
                <div className={`pr-judge ${turn.verdict}`}>
                  {judging ? <CircleNotch /> : turn.verdict === 'bad' ? <Warning /> : <Check />}
                  <b>{turn.score}</b>
                  <p>{turn.note}</p>
                </div>
              </div>
            );
          })}

          {done && (
            <div className="pr-summary">
              <Flag />
              <div>
                <b>本轮最该改的一句</b>
                <p>
                  第三轮已经把客户带到「发方案」这一步，却又用折扣把话题拉回价格。
                  这里应该直接确认：方案发过去后哪天可以一起过一遍。
                </p>
              </div>
            </div>
          )}
        </div>
      </div>

      <aside className="demo-rail">
        <div className="rail-title">
          <span>逐轮评判</span>
          <StatusPill tone={done ? 'green' : 'orange'}>{done ? `结分 ${final}` : '进行中'}</StatusPill>
        </div>

        <ol className="plan-list">
          {TURNS.map((turn, i) => {
            const visible = i < tick;
            return (
              <li key={turn.sales.slice(0, 8)} className={visible ? 'done' : ''}>
                {visible ? <Check /> : <span>{i + 1}</span>}第 {i + 1} 轮 · {turn.score} 分
              </li>
            );
          })}
        </ol>

        <div className="dim-list">
          {DIMENSIONS.map((dim, i) => {
            const score = scoreOf(i);
            return (
              <div key={dim.name} className={`dim-row ${score > 0 ? 'on' : ''}`}>
                <span>{dim.name}</span>
                <i>
                  <em style={{ width: `${score * 10}%` }} />
                </i>
                <b>{score > 0 ? score.toFixed(1) : '—'}</b>
              </div>
            );
          })}
        </div>

        <div className="rail-callout">
          <Target />
          <div>
            <b>对练不是选择题</b>
            <p>AI 扮演真实客户逐轮回话，每轮都会指出你这句话会带来什么后果。</p>
          </div>
        </div>
      </aside>
    </div>
  );
}
