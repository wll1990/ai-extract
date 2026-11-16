'use client';

import { useEffect, useRef, useState } from 'react';
import { Check, CircleNotch, GraduationCap, SealCheck, Sparkle, UserCircle } from '@phosphor-icons/react';
import { StatusPill, type PillTone } from './primitives';

interface Step {
  key: string;
  tag: string;
  tone: PillTone;
  title: string;
  body: string;
  meta: string;
}

const STEPS: Step[] = [
  {
    key: 'gap',
    tag: '缺口',
    tone: 'orange',
    title: '14 天里 7 次没答上来',
    body: '团队反复问「客户拿竞品报价来压价，怎么回」，分身 7 次回答超出范围，都建议找本人确认。',
    meta: '来自分身对话的未命中记录 · 自动汇总',
  },
  {
    key: 'coach',
    tag: '教练补充',
    tone: 'purple',
    title: '教练追加一段，不改写原结论',
    body: '「不要接报价。先问对方拿的是哪一版配置——价差里有多少是服务范围差异，多少是单纯压价。」',
    meta: '教练 陈锋 · 2026-10-28 · 原始结论与依据保持不变',
  },
  {
    key: 'resolve',
    tag: '补进经验库',
    tone: 'green',
    title: '缺口标记为已解决',
    body: '把这次答不上来的问题补成经验条目，重新审核发布后进入检索池，下次同样的问题就能答。',
    meta: '在缺口列表里处理 · 状态 open → resolved',
  },
  {
    key: 'training',
    tag: '培训认证',
    tone: 'green',
    title: '6 人用这条新经验对练，5 人通过',
    body: '批次 A 学员拿「竞品压价」这个场景做对练，通过的人拿到认证，没过的 1 人自动进入补练。',
    meta: '试点团队 · 10 月批次 · 认证可用于上岗要求',
  },
];

const STEP_MS = 1200;

export function CoachScene({ playing }: { playing: boolean }) {
  const [tick, setTick] = useState(0);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    if (!playing) {
      setTick(0);
      return;
    }
    // 开启「减少动态效果」时直接给出最终结果，不做逐步推进
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
      setTick(STEPS.length);
      return;
    }
    setTick(0);
    timer.current = setInterval(() => {
      setTick(prev => {
        if (prev >= STEPS.length) {
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

  const done = tick >= STEPS.length;

  return (
    <div className="demo-stage">
      <div className="demo-chat">
        <div className="demo-mode">
          <div>
            <span className="demo-scene-label">一条缺口，怎么变成团队资产</span>
          </div>
          <StatusPill tone="gray">缺口 → 补充 → 审核 → 认证</StatusPill>
        </div>

        <div className="coach-scene">
          <div className="cs-gap">
            <span>本周期未答上的问题</span>
            <b className={tick >= 3 ? 'bump' : ''}>{tick >= 3 ? '6' : '7'}</b>
            <small>{tick >= 3 ? '已解决 1 条' : '按出现次数累计'}</small>
          </div>

          <ol className="cs-steps">
            {STEPS.map((step, i) => {
              const visible = i < tick;
              const running = i === tick;
              return (
                <li key={step.key} className={`cs-step ${visible ? 'in' : ''} ${running ? 'running' : ''}`}>
                  <div className="cs-marker">
                    {visible ? <Check weight="bold" /> : running ? <CircleNotch /> : <span>{i + 1}</span>}
                  </div>
                  <div className="cs-body">
                    <header>
                      <StatusPill tone={step.tone}>{step.tag}</StatusPill>
                      <b>{step.title}</b>
                    </header>
                    <p>{step.body}</p>
                    <small>{step.meta}</small>
                  </div>
                </li>
              );
            })}
          </ol>

          {done && (
            <div className="cs-result">
              <SealCheck weight="fill" />
              <div>
                <b>缺口 → 补充 → 审核 → 训练，闭环走完</b>
                <p>下一次再有人问「竞品压价」，分身能直接给出有依据的回答，而不是「请找本人确认」。</p>
              </div>
            </div>
          )}
        </div>
      </div>

      <aside className="demo-rail">
        <div className="rail-title">
          <span>这条经验谁做的</span>
          <StatusPill tone={done ? 'green' : 'orange'}>{done ? '已完成' : '进行中'}</StatusPill>
        </div>

        <div className="cs-people">
          <div><UserCircle /><div><b>教练 陈锋</b><small>补内容，不改写 AI 结论</small></div></div>
          <div><Sparkle /><div><b>销售负责人</b><small>补充的经验审核发布后才进检索池</small></div></div>
          <div><GraduationCap /><div><b>6 名学员</b><small>用对练验证真的学会了</small></div></div>
        </div>

        <div className="rail-callout">
          <Check />
          <div>
            <b>补充是追加，不是改写</b>
            <p>原始访谈、原始结论、每一次修改都留痕，任何人可以回头核对。</p>
          </div>
        </div>
      </aside>
    </div>
  );
}
