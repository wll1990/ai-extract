'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { ArrowLeft, ArrowRight, Check, Sparkle } from '@phosphor-icons/react';
import { PortalHeader } from '@/components/portal/PortalHeader';
import { Reveal } from '@/components/portal/Reveal';
import { track } from '@/lib/analytics';
import { getToken } from '@/lib/storage';

interface Chapter {
  id: string;
  no: string;
  eyebrow: string;
  title: string;
  lead: string;
  points: string[];
  note?: string;
  demo?: string;
}

const CHAPTERS: Chapter[] = [
  {
    id: 'experience',
    no: '01',
    eyebrow: '经验怎么来',
    title: '不是让专家写文档，是让 AI 把一次真实决策问出来。',
    lead: 'AI 萃取师按关键事件访谈，围绕「当时发生了什么、你怎么判断、如果重来会怎么做」往下追。四阶段问完，输出一份六章报告。',
    points: [
      '六章结构：案例、步骤、决策、心法、边界、清单',
      '报告里每一条都要能对应到访谈原话，不是概括',
      '自动拆成经验颗粒：场景、专家思考、标准话术、常见错误、适用条件',
      '专家本人审核通过后才会发布，能回答什么、不能回答什么由他说了算',
    ],
    note: '一条颗粒就是一条可以被检索的依据。分身回答时引用的，就是它。',
  },
  {
    id: 'chat',
    no: '02',
    eyebrow: '分身怎么用',
    title: '三种用法：问结论、自由聊、被客户练。',
    lead: '同一个分身在三种模式下工作，用的都是同一套经验库，不存在「问答一套、对练另一套」。',
    points: [
      '问答：先给结论，再给依据；检索不到就明确说不知道',
      '讨论：可以追问、反驳、要反例，按对话继续展开',
      '对练：AI 扮演你设定的客户角色，逐轮回话并评判',
      '对练的四个维度取自真实评分标准：策略正确性、话术说服力、客户洞察、话术复用',
    ],
    demo: '第 1、2 幕',
  },
  {
    id: 'review',
    no: '03',
    eyebrow: '复盘与闭环',
    title: '一段对话，拆成五个部分，再变成动作。',
    lead: '把客户沟通切成段落，逐段判断，输出做得好、需改进、风险、客户信号、下一步。',
    points: [
      '每条结论都绑定原文段落，点开就能看到是哪句话支撑的',
      '客户信号区分「客户原话」「销售转述」「AI 推断」，不混为一谈',
      '下一步可以直接转成线索和任务，不用另外抄一遍',
      '转线索、建任务、发外部消息之前，都会先把内容给你确认',
    ],
    demo: '第 3、5 幕',
  },
  {
    id: 'improve',
    no: '04',
    eyebrow: '经验怎么变好',
    title: '答不上来的问题，会被记下来。',
    lead: '分身检索不到依据时会拒答，同时把这个问题记成知识缺口，按出现次数累计。',
    points: [
      '缺口列表按出现次数排序，反复问的排在前面',
      '教练可以追加批注：是追加，不改写原有结论，原始记录始终可查',
      '补充的经验经审核发布后才进入检索池',
      '新经验用对练验证：学员真的练过、通过了，才算学会',
    ],
    note: '这条链路对应演示的第 4 幕。缺口、补充、审核、认证都能在系统里查到记录。',
    demo: '第 4 幕',
  },
  {
    id: 'deploy',
    no: '05',
    eyebrow: '怎么接入',
    title: '不换工具，按角色下发。',
    lead: '每家企业一个独立租户；权限在后端统一下发，前端不维护第二份映射。',
    points: [
      '经验与对话按企业隔离，别家企业互相看不到',
      '销冠、销售、教练、管理员看到的内容不同',
      '飞书 / 企业微信 / 钉钉机器人已内置，日常在群里就能问分身',
      '报价与部署形态按团队规模在演示时给',
    ],
  },
];

export default function ProductPage() {
  const router = useRouter();
  const [loggedIn, setLoggedIn] = useState(false);

  useEffect(() => {
    setLoggedIn(Boolean(getToken()));
    track('product_view', {});
  }, []);

  const toDemo = () => {
    track('cta_click', { action: 'demo', from: 'product' });
    router.push('/portal#demo');
  };

  return (
    <div className="public-page">
      <div className="top-ribbon">REAL EXPERIENCE × AI · 把真人经验变成可对话的分身</div>
      <PortalHeader loggedIn={loggedIn} />

      <main>
        <section className="product-hero">
          <Reveal>
            <button type="button" className="back-link" onClick={() => router.push('/portal')}>
              <ArrowLeft /> 回到首页
            </button>
            <span className="eyebrow"><Sparkle weight="fill" />产品说明</span>
            <h1>从一个问题，<br />到一条能被复用的经验。</h1>
            <p>
              这页讲的是它到底怎么运作：经验从哪来、分身怎么用、复盘怎么闭环、答案错了怎么变好。
              每一节都能在系统里找到对应的记录。
            </p>
            <div className="hero-actions">
              <button type="button" className="button button-primary" onClick={toDemo}>
                看五段演示 <ArrowRight />
              </button>
              <button type="button" className="button button-secondary" onClick={() => router.push('/portal#contact')}>
                预约 20 分钟
              </button>
            </div>
          </Reveal>
        </section>

        <section className="product-chapters">
          {CHAPTERS.map((ch, i) => (
            <Reveal as="article" key={ch.id} className="product-chapter" delay={i * 40}>
              <span className="chapter-no">{ch.no}</span>
              <div className="chapter-body">
                <span className="eyebrow">{ch.eyebrow}</span>
                <h2>{ch.title}</h2>
                <p className="chapter-lead">{ch.lead}</p>
                <ul>
                  {ch.points.map(point => (
                    <li key={point}><Check />{point}</li>
                  ))}
                </ul>
                {ch.note && <p className="chapter-note">{ch.note}</p>}
                {ch.demo && <span className="chapter-demo">对应演示 · {ch.demo}</span>}
              </div>
            </Reveal>
          ))}
        </section>

        <section className="product-cta">
          <Reveal>
            <h2>看完还是不确定，就用你自己的一个客户跑一遍。</h2>
            <p>20 分钟，不准备材料，跑完你自己判断。</p>
            <div className="hero-actions">
              <button type="button" className="button button-primary" onClick={() => router.push('/portal#contact')}>
                预约演示 <ArrowRight />
              </button>
              <button type="button" className="button button-secondary" onClick={toDemo}>
                先看演示
              </button>
            </div>
          </Reveal>
        </section>
      </main>

      <footer className="public-footer">
        <button className="brand" type="button" onClick={() => router.push('/portal')}>
          <span className="brand-mark"><Sparkle weight="fill" /></span>
          <span>MindForge</span>
        </button>
        <p>真人经验驱动的 AI 分身网络</p>
        <button type="button" className="button button-primary" onClick={() => router.push('/portal#contact')}>
          预约一次演示 <ArrowRight />
        </button>
      </footer>
    </div>
  );
}
