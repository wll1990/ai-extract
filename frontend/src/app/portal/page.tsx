'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import {
  ArrowRight, Check, CheckCircle, ChatCircleDots,
  Quotes, ShieldCheck, Sparkle, Target, UsersThree,
} from '@phosphor-icons/react';
import { DemoPlayer } from '@/components/portal/DemoPlayer';
import { HeroAgentCard } from '@/components/portal/HeroAgentCard';
import { DemoRequestForm } from '@/components/portal/DemoRequestForm';
import { PortalHeader } from '@/components/portal/PortalHeader';
import { PortalQuickNav } from '@/components/portal/PortalQuickNav';
import { Reveal } from '@/components/portal/Reveal';
import { StatusPill } from '@/components/portal/primitives';
import { getToken } from '@/lib/storage';
import { track } from '@/lib/analytics';
import { PILOT_METRICS, PILOT_METRICS_NOTE } from '@/lib/portal/pilot';
import { PILOT_COMMITMENT } from '@/lib/portal/commitment';

type Audience = 'external' | 'internal' | 'investor';
type Action = 'contact' | 'demo' | 'proof' | 'enter' | 'how' | 'setup';

const COPY: Record<Audience, {
  eyebrow: string;
  title: string;
  sub: string;
  primary: { label: string; action: Action };
  secondary: { label: string; action: Action };
  /** 价值证明区第二段：同一套事实，三种读者关心的点不一样 */
  proofLine: string;
}> = {
  external: {
    eyebrow: '面向 B2B 大客户销售团队',
    title: '把销冠的经验，\n变成团队当天就能用的回答。',
    sub: '新人遇到客户问题，先问销冠的分身：每条回答回到原话，超出范围会明说不知道；聊完直接生成复盘、线索和任务。',
    primary: { label: '预约 20 分钟演示', action: 'contact' },
    secondary: { label: '看一次现场演示', action: 'demo' },
    proofLine: '带一个你正在卡的客户来。20 分钟里用你的场景走完「问分身 → 出复盘 → 转下一步」，跑完你自己判断值不值得试点。',
  },
  internal: {
    eyebrow: '内部试点',
    title: '让销冠的经验，\n先在你团队里跑起来。',
    sub: '30 天试点：选一个团队，跑通“问销冠 → 复盘 → 线索/任务”一条完整链路，再决定是否推广。',
    primary: { label: '进入系统', action: 'enter' },
    secondary: { label: '看一次现场演示', action: 'demo' },
    proofLine: '试点要看的不是使用率，是三件事：新人少问了几次人、复盘后有没有形成可执行的下一步、答不上来的问题有没有被补上。',
  },
  investor: {
    eyebrow: '价值与指标',
    title: '把隐性经验，\n变成可复用的组织资产。',
    sub: '经验沉淀、分身对话、销售复盘共用一套经验库；每一次使用都留下可追溯的证据与可量化的结果。',
    primary: { label: '看我们怎么证明', action: 'proof' },
    secondary: { label: '看一次现场演示', action: 'demo' },
    proofLine: '我们不提供预测数字。三个指标都可以从库里直接查出来：引用命中率、答不上的问题数、复盘转成线索的比例。',
  },
};

/**
 * 试点期没有可公开的客户案例，所以不摆数字，改成三条「可以当场验」的事实。
 * 每条都写清楚怎么验——客户能自己动手，就不需要相信我们的说法。
 */
const PROOFS: Array<{ title: string; how: string }> = [
  {
    title: '每条回答都能点回原话',
    how: '点开任意一条引用，展开的是访谈原文，不是 AI 复述。原文说不到的地方，它不会替你补。',
  },
  {
    title: '超出范围它会说不知道',
    how: '现场问一个我们没采集过的问题，看它是否拒答，并建议你去找本人确认，而不是编一个像样的答案。',
  },
  {
    title: '关键动作必须过你确认',
    how: '转线索、建任务、发外部消息，都会先把内容给你看。你不点确认，它不会自己执行。',
  },
];

export default function PortalPage() {
  const router = useRouter();
  const [loggedIn, setLoggedIn] = useState(false);
  const [audience, setAudience] = useState<Audience>('external');

  useEffect(() => {
    setLoggedIn(Boolean(getToken()));
    const value = new URLSearchParams(window.location.search).get('audience');
    const next: Audience = value === 'internal' || value === 'investor' ? value : 'external';
    setAudience(next);
    track('portal_view', { audience: next, logged_in: Boolean(getToken()) });
  }, []);

  const copy = COPY[audience];

  /** 降低动态效果偏好：平滑滚动会让人不适，改成瞬时跳转 */
  const prefersReduced = () =>
    typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

  const go = (action: Action) => {
    track('cta_click', { action, audience });
    if (action === 'enter') {
      router.push(loggedIn ? '/' : '/login?redirect=/portal');
      return;
    }
    document.getElementById(action)?.scrollIntoView({
      behavior: prefersReduced() ? 'auto' : 'smooth',
      block: 'start',
    });
  };

  const wechatQr = process.env.NEXT_PUBLIC_DEMO_WECHAT_QR;
  const wechatId = process.env.NEXT_PUBLIC_DEMO_WECHAT_ID;

  return (
    <div className="public-page">
      <div className="top-ribbon">REAL EXPERIENCE × AI · 把真人经验变成可对话的分身</div>
      <PortalHeader loggedIn={loggedIn} />

      <main>
        {/* 首屏 */}
        <section className="landing-hero">
          <div className="hero-copy">
            <span className="eyebrow"><Sparkle weight="fill" />{copy.eyebrow}</span>
            <h1>
              {copy.title.split('\n').map((line, i) => (
                <span key={line}>{i > 0 && <br />}{line}</span>
              ))}
            </h1>
            <p>{copy.sub}</p>
            <div className="hero-actions">
              <button type="button" className="button button-primary" onClick={() => go(copy.primary.action)}>
                {copy.primary.label} <ArrowRight />
              </button>
              <button type="button" className="button button-secondary" onClick={() => go(copy.secondary.action)}>
                {copy.secondary.label}
              </button>
            </div>
            <div className="trust-row">
              <span><ShieldCheck weight="fill" />回答可追溯到原话</span>
              <span><Quotes weight="fill" />超出范围会明说</span>
              <span><CheckCircle weight="fill" />转线索/建任务前由你确认</span>
            </div>
          </div>

          <div className="hero-scene">
            <div className="portrait-stage">
              <img
                src="/assets/expert-network-hero.jpg"
                alt="经验被沉淀成可对话的分身"
                width={1400}
                height={933}
                fetchPriority="high"
              />
            </div>
            <div className="energy-line line-a" />
            <div className="energy-line line-b" />
            <HeroAgentCard />
          </div>
        </section>

        {/* 价值证明：有试点数据就摆数据，没有就摆可当场验证的事实 */}
        <section className={PILOT_METRICS.length > 0 ? 'metric-band' : 'proof-section'} id="proof">
          {PILOT_METRICS.length > 0 ? (
            <>
              <Reveal className="metric-band-head">
                <span className="eyebrow">试点数据</span>
                <p>{PILOT_METRICS_NOTE}</p>
              </Reveal>
              <div className="metric-grid">
                {PILOT_METRICS.map((metric, i) => (
                  <Reveal as="article" key={metric.label} delay={i * 60}>
                    <strong>{metric.value}</strong>
                    <span>{metric.label}</span>
                    <small>{metric.note}</small>
                  </Reveal>
                ))}
              </div>
            </>
          ) : (
            <>
              <Reveal className="proof-head">
                <span className="eyebrow">试点期 · 我们不放截图数字</span>
                <h2>
                  <span className="head-line">现在还没有可公开的客户案例。</span>
                  <span className="head-line">那就别听我们说，当场跑一遍。</span>
                </h2>
                <p>{copy.proofLine}</p>
              </Reveal>

              <div className="proof-grid">
                {PROOFS.map((item, i) => (
                  <Reveal as="article" key={item.title} delay={i * 80}>
                    <span className="proof-no">{String(i + 1).padStart(2, '0')}</span>
                    <b>{item.title}</b>
                    <p>{item.how}</p>
                  </Reveal>
                ))}
              </div>

              {/* 用时间线代替没有的结果数字：承诺的是过程，不是我们没有的数据 */}
              <Reveal className="promise-strip" delay={200}>
                <div>
                  <b>第一周访谈完销冠，第二周经验审核上线，第三周新人开始问。</b>
                  <span>30 天后你看到的是自己团队的数据，不是我们的。</span>
                </div>
                {PILOT_COMMITMENT.enabled && (
                  <div>
                    <b>{PILOT_COMMITMENT.title}</b>
                    <span>{PILOT_COMMITMENT.detail}</span>
                  </div>
                )}
              </Reveal>

              <Reveal className="proof-cta" delay={240}>
                <button type="button" className="button button-primary" onClick={() => go('contact')}>
                  带一个客户来现场验证 <ArrowRight />
                </button>
                <button type="button" className="button button-secondary" onClick={() => go('demo')}>
                  先在演示里看一遍
                </button>
                <small>演示用的是脱敏示例数据，不涉及任何真实客户；每一幕都可以重播。</small>
              </Reveal>
            </>
          )}
        </section>

        {/* 核心能力：三张卡，各自标注对应哪一幕演示 */}
        <section className="value-section" id="capabilities">
          <Reveal className="section-heading">
            <span className="eyebrow">三件事，一条链路</span>
            <h2>能问、能练、能复盘，最后沉得下来。</h2>
            <p>问销冠是入口，对练是训练，复盘是动作；沉淀与补充在下面单独讲。</p>
          </Reveal>

          <div className="value-grid value-grid-3">
            <Reveal as="article" className="value-card gradient-card" delay={40}>
              <ChatCircleDots />
              <StatusPill tone="white">最常用</StatusPill>
              <h3>问销冠</h3>
              <p>把销冠的判断变成随时能问的回答。每条结论带来源；没有依据时直接说不知道。</p>
              <ul>
                <li><Check />回答回到访谈原话</li>
                <li><Check />标出适用条件与反例</li>
                <li><Check />可继续追问或转成对练</li>
              </ul>
              <span className="card-demo-map">对应演示 · 第 1 幕「问销冠」</span>
              <button type="button" className="link-button white" onClick={() => go('demo')}>
                看它怎么回答 <ArrowRight />
              </button>
            </Reveal>

            <Reveal as="article" className="value-card light-card" delay={100}>
              <UsersThree />
              <StatusPill tone="purple">新人最快上手</StatusPill>
              <h3>实战对练</h3>
              <p>AI 扮演你的客户，逐轮回话并指出这句会带来什么后果，而不是打分完就结束。</p>
              <ul>
                <li><Check />客户角色可设定</li>
                <li><Check />每一轮都给出判断依据</li>
                <li><Check />结算时指出最该改的一句</li>
              </ul>
              <span className="card-demo-map">对应演示 · 第 2 幕「实战对练」</span>
              <button type="button" className="link-button" onClick={() => go('demo')}>
                看一局对练 <ArrowRight />
              </button>
            </Reveal>

            <Reveal as="article" className="value-card light-card" delay={160}>
              <Target />
              <StatusPill tone="orange">业务闭环</StatusPill>
              <h3>复盘与下一步</h3>
              <p>一段对话变成五个部分：做得好、需改进、风险、客户信号、下一步，可直接转线索和任务。</p>
              <ul>
                <li><Check />每条判断都能展开原文</li>
                <li><Check />下一步可直接变线索和任务</li>
                <li><Check />执行前必须由你确认</li>
              </ul>
              <span className="card-demo-map">对应演示 · 第 3、5 幕「复盘」「转下一步」</span>
              <button type="button" className="link-button" onClick={() => go('demo')}>
                看一次复盘 <ArrowRight />
              </button>
            </Reveal>
          </div>
        </section>

        {/* 如何工作 */}
        <section className="how-section" id="how">
          <Reveal>
            <span className="eyebrow">ONE QUESTION, THREE STEPS</span>
            <h2>不用先学功能，直接说你要推进什么。</h2>
          </Reveal>
          <div className="how-steps">
            {[
              ['01', '说出目标', '用一句话描述你卡住的客户、决策或沟通。'],
              ['02', '确认方案', '系统先给出将调用哪些经验、产出什么，再等你确认。'],
              ['03', '得到行动', '拿到带依据的建议、可执行的下一步，以及可追踪的线索和任务。'],
            ].map(([no, title, desc], i) => (
              <Reveal as="article" key={no} delay={i * 80}>
                <span>{no}</span>
                <h3>{title}</h3>
                <p>{desc}</p>
              </Reveal>
            ))}
          </div>
        </section>

        {/* 可信 / 沉淀与补充 */}
        <section className="trust-section" id="trust">
          <Reveal>
            <span className="eyebrow">沉淀与补充 · 可信从知识生产开始</span>
            <h2>不是生成一个像专家的回答。</h2>
            <p>
              每条重要建议，都能回到一个真人、一段经历和一次明确的审核。
              答不上的问题会被记成缺口，补充后再回流到经验库——这条链路就是演示的第 4 幕。
            </p>
          </Reveal>
          <div className="trust-grid">
            {[
              ['01', '真人验证', '身份、从业经历与代表案例'],
              ['02', '经验萃取', '深访沉淀事实、原则与边界'],
              ['03', '专家审核', '确认依据、语气与不能回答的范围'],
              ['04', '持续更新', '缺口汇总 → 教练补充 → 审核发布 → 对练认证'],
            ].map(([no, title, desc], i) => (
              <Reveal as="article" key={no} delay={i * 60}>
                <span>{no}</span>
                <h3>{title}</h3>
                <p>{desc}</p>
              </Reveal>
            ))}
          </div>
        </section>

        {/* 动态演示 */}
        <section className="demo-section" id="demo">
          <Reveal className="section-heading">
            <span className="eyebrow">LIVE DEMO</span>
            <h2>五段演示，从问一句到形成能力。</h2>
            <p>
              按 1 到 5 的顺序看最清楚。默认播放演示回放，
              登录后可切换成真实后端现场生成。
            </p>
          </Reveal>
          <Reveal delay={80}>
            <DemoPlayer loggedIn={loggedIn} />
          </Reveal>
        </section>

        {/* 数据怎么处理 */}
        <section className="compliance-section">
          <Reveal className="section-heading">
            <span className="eyebrow">数据怎么处理</span>
            <h2>客户对话是最敏感的数据，先说清楚。</h2>
          </Reveal>
          <div className="compliance-grid">
            {[
              ['客户对话只用于本次复盘', '不进入训练语料，不用于其它客户。'],
              ['教练看原文需单独授权', '默认只看到摘要，授权后才可查看完整对话。'],
              ['可随时删除', '对话、报告与分身对话记录都可以删除。'],
              ['分身只回答已确认范围', '超出范围会明确说不知道，并建议找本人确认。'],
            ].map(([title, desc], i) => (
              <Reveal as="article" key={title} delay={i * 60}>
                <ShieldCheck />
                <div>
                  <b>{title}</b>
                  <p>{desc}</p>
                </div>
              </Reveal>
            ))}
          </div>
        </section>

        {/* 怎么接入 */}
        <section className="setup-section" id="setup">
          <Reveal className="section-heading">
            <span className="eyebrow">怎么接入</span>
            <h2>不用换掉你现有的工具。</h2>
            <p>三件和采购直接相关的事实；报价和部署形态，按你的规模在演示时给。</p>
          </Reveal>
          <div className="setup-grid">
            {[
              { icon: <ShieldCheck />, title: '独立企业空间', desc: '每家企业一个独立租户，经验和对话不互通。别家企业的数据，你们互相看不到。' },
              { icon: <UsersThree />, title: '按角色下发权限', desc: '销冠、销售、教练、管理员看到的东西不一样，权限在后端统一下发。' },
              { icon: <ChatCircleDots />, title: '已有 IM 能接', desc: '飞书、企业微信、钉钉机器人都已内置，不用另装 App；日常在群里就能问分身。' },
            ].map((item, i) => (
              <Reveal as="article" key={item.title} delay={i * 60}>
                {item.icon}
                <b>{item.title}</b>
                <p>{item.desc}</p>
              </Reveal>
            ))}
          </div>
        </section>

        {/* 怎么开始：改成横向路径，和上面的三列区分开 */}
        <section className="path-section" id="rollout">
          <Reveal className="section-heading">
            <span className="eyebrow">怎么开始</span>
            <h2>先跑通一个团队，再谈推广。</h2>
          </Reveal>
          <div className="path-track">
            {[
              ['01', '试点 1 个团队 · 30 天', '选 1 位销冠 + 5–10 位销售，跑通问、复盘、线索任务。'],
              ['02', '部门推广', '把验证过的经验与复盘模板复制到相邻团队。'],
              ['03', '全员使用', '接入飞书 / 企业微信 / 钉钉，按角色下发权限与看板。'],
            ].map(([no, title, desc], i) => (
              <Reveal as="article" className="path-step" key={no} delay={i * 80}>
                <span className="path-dot">{no}</span>
                <h3>{title}</h3>
                <p>{desc}</p>
              </Reveal>
            ))}
          </div>
        </section>

        {/* 预约 */}
        <section className="contact-section" id="contact">
          <Reveal className="contact-copy">
            <span className="eyebrow">预约演示</span>
            <h2>约 20 分钟，看你自己的一个客户。</h2>
            <p>不用准备材料。带一个正在卡住的客户，我们现场用你的场景跑一遍：问分身、出复盘、转下一步。</p>
            <ul>
              <li><Check />20 分钟，不占用更多时间</li>
              <li><Check />用你的真实场景，不用看通用幻灯片</li>
              <li><Check />结束后你可以自己判断值不值得试点</li>
            </ul>

            {/* 证据贴着决策点：把「可以当场验」搬到表单旁边，不要让人滚四屏回去找 */}
            <div className="contact-proof">
              <b>不信你可以当场验</b>
              <span>带一个客户来，20 分钟跑完：问分身、出复盘、转下一步。</span>
              <span>问它一个没采集过的问题，看它是不是会说不知道。</span>
            </div>
          </Reveal>

          <Reveal delay={80} className="contact-side">
            <DemoRequestForm />
            {wechatQr && (
              <div className="wechat-card">
                <img src={wechatQr} alt="企业微信二维码" />
                <div>
                  <b>也可以直接加微信</b>
                  <p>{wechatId ? `微信号：${wechatId}` : '扫码添加，备注「演示」'}</p>
                </div>
              </div>
            )}
          </Reveal>
        </section>

        {/* 双入口已移除：一页只推一个决定。访客要「进入系统」时，顶栏一直是可见的。 */}
      </main>

      <footer className="public-footer">
        <button className="brand" type="button" onClick={() => router.push('/portal')}>
          <span className="brand-mark"><Sparkle weight="fill" /></span>
          <span>MindForge</span>
        </button>
        <p>真人经验驱动的 AI 分身网络</p>
        <nav className="footer-links">
          <button type="button" onClick={() => router.push('/portal/product')}>产品说明</button>
          <button type="button" onClick={() => router.push('/portal/notes/interview-to-grain')}>方法笔记</button>
          <button type="button" onClick={() => router.push('/portal/evaluation')}>自评测</button>
        </nav>
        <button type="button" className="button button-primary" onClick={() => go('contact')}>
          预约一次演示 <ArrowRight />
        </button>
      </footer>

      <PortalQuickNav onDemo={() => go('demo')} />
    </div>
  );
}
