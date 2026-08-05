<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>萃取档案 · ${ownerName}</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{
  font-family:-apple-system,BlinkMacSystemFont,'Inter','PingFang SC','Microsoft YaHei',sans-serif;
  background:linear-gradient(135deg,#f5f3ff 0%,#fff 50%,#eff6ff 100%);
  background-attachment:fixed;color:#0f172a;line-height:1.7;
  -webkit-font-smoothing:antialiased;
}
.container{max-width:900px;margin:0 auto;padding:24px 16px}

/* Nav */
.nav{
  position:sticky;top:0;z-index:10;
  background:rgba(255,255,255,0.85);backdrop-filter:saturate(180%) blur(20px);
  -webkit-backdrop-filter:saturate(180%) blur(20px);
  border-bottom:1px solid rgba(99,102,241,0.08);
  padding:14px 24px;display:flex;justify-content:space-between;align-items:center
}
.nav-title{
  font-size:14px;font-weight:700;
  background:linear-gradient(135deg,#6366f1,#8b5cf6,#ec4899);
  -webkit-background-clip:text;background-clip:text;-webkit-text-fill-color:transparent;
  letter-spacing:0.5px
}
.nav-links{display:flex;gap:16px;font-size:12px}
.nav-links a{color:#64748b;text-decoration:none;transition:color .15s}
.nav-links a:hover{color:#6366f1}
.nav-btn{
  background:linear-gradient(135deg,#6366f1,#8b5cf6);color:#fff;border:none;
  padding:8px 16px;border-radius:8px;font-size:12px;font-weight:600;cursor:pointer;
  box-shadow:0 2px 8px rgba(99,102,241,0.25);transition:transform .15s,box-shadow .15s
}
.nav-btn:hover{transform:translateY(-1px);box-shadow:0 4px 12px rgba(99,102,241,0.35)}

/* Page */
.page{
  padding:48px 40px;margin:20px 0;border-radius:16px;background:#fff;
  box-shadow:0 1px 3px rgba(15,23,42,0.04),0 8px 24px rgba(99,102,241,0.06);
  border:1px solid rgba(99,102,241,0.06)
}

/* ═══ Cover ═══ */
.cover{
  background:linear-gradient(135deg,#f5f3ff 0%,#ede9fe 30%,#fff 60%,#e0f2fe 100%);
  color:#0f172a;min-height:580px;position:relative;overflow:hidden;
  display:flex;flex-direction:column;justify-content:space-between
}
.cover::before{
  content:"";position:absolute;top:-80px;right:-80px;width:240px;height:240px;
  border-radius:50%;background:radial-gradient(circle,rgba(139,92,246,0.18),transparent 70%);pointer-events:none
}
.cover-top{display:flex;flex-direction:column;gap:6px;position:relative;z-index:1}
.cover-eyebrow{
  font-size:11px;font-weight:700;
  background:linear-gradient(135deg,#6366f1,#ec4899);
  -webkit-background-clip:text;background-clip:text;-webkit-text-fill-color:transparent;
  letter-spacing:3px;text-transform:uppercase
}
.cover-divider{width:40px;height:3px;background:linear-gradient(90deg,#6366f1,#ec4899);border-radius:2px;margin:12px 0 24px}
.cover-tagline{font-size:14px;color:#64748b;font-style:italic;margin-bottom:12px}
.cover-name{
  font-size:36px;font-weight:800;margin:6px 0;letter-spacing:1px;
  background:linear-gradient(135deg,#0f172a,#312e81);
  -webkit-background-clip:text;background-clip:text;-webkit-text-fill-color:transparent
}
.cover-role{font-size:13px;color:#64748b}
.cover-inscription{
  border-top:1px dashed rgba(99,102,241,0.2);padding-top:20px;margin-top:24px;position:relative;z-index:1
}
.cover-inscription-label{
  font-size:10px;color:#8b5cf6;letter-spacing:2.5px;margin-bottom:10px;font-weight:700;text-transform:uppercase
}
.cover-inscription-text{
  font-size:18px;line-height:1.6;font-style:italic;color:#1e1b4b;
  padding:14px 20px;background:rgba(255,255,255,0.6);border-radius:10px;border-left:4px solid #8b5cf6
}
.cover-footer{
  display:flex;justify-content:space-between;font-size:11px;color:#64748b;
  border-top:1px solid rgba(99,102,241,0.12);padding-top:16px;margin-top:24px;position:relative;z-index:1;font-weight:500
}

/* ═══ Dashboard ═══ */
.dashboard{background:linear-gradient(180deg,#fff 0%,#f8fafc 100%)}
.sec-eyebrow{
  font-size:11px;font-weight:700;
  background:linear-gradient(135deg,#6366f1,#ec4899);
  -webkit-background-clip:text;background-clip:text;-webkit-text-fill-color:transparent;
  letter-spacing:2.5px;text-transform:uppercase
}
.sec-title{font-size:28px;font-weight:800;color:#0f172a;margin:10px 0 8px;letter-spacing:-0.5px}
.sec-subtitle{font-size:13px;color:#475569;margin-bottom:28px;line-height:1.8}
.stat-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:16px;margin:24px 0}
.stat-card{
  background:#fff;padding:20px 22px;border-radius:12px;
  box-shadow:0 1px 3px rgba(15,23,42,0.04),0 4px 12px rgba(99,102,241,0.06);
  transition:transform .15s,box-shadow .15s
}
.stat-card:hover{transform:translateY(-2px);box-shadow:0 2px 6px rgba(15,23,42,0.06),0 8px 20px rgba(99,102,241,0.1)}
.stat-label{font-size:12px;color:#64748b;text-transform:uppercase;letter-spacing:1.5px;font-weight:600}
.stat-number{font-size:36px;font-weight:800;margin:6px 0;line-height:1.1}
.stat-desc{font-size:12.5px;color:#334155;line-height:1.6;text-align:justify}
.status-bar{
  padding:14px 18px;border-radius:10px;color:#fff;font-size:14px;text-align:center;
  margin-top:24px;font-weight:600;letter-spacing:0.5px;
  background:linear-gradient(135deg,#6366f1,#8b5cf6)
}

/* ═══ Graph ═══ */
.graph-page{background:linear-gradient(180deg,#fff 0%,#faf5ff 100%);text-align:center}
.graph-svg-wrap{max-width:360px;margin:0 auto 20px}
.graph-svg{width:100%;height:auto}
.graph-event{font-size:13px;color:#64748b;margin-bottom:12px;font-style:italic}
.graph-inscription{
  margin-top:24px;padding:16px 22px;background:#fff;border-left:4px solid #f59e0b;
  border-radius:0 10px 10px 0;text-align:left;box-shadow:0 1px 3px rgba(15,23,42,0.04)
}
.graph-inscription-label{font-size:11px;color:#b45309;margin-bottom:6px;font-weight:600;text-transform:uppercase;letter-spacing:1.5px}
.graph-inscription-text{font-size:15px;color:#1e1b4b;font-style:italic;line-height:1.6}
.graph-hint{font-size:12px;color:#94a3b8;margin-top:14px}

/* ═══ Knowledge Cards ═══ */
.featured{background:linear-gradient(180deg,#fff 0%,#f0f9ff 100%)}
.kcard{
  background:#fff;border:1px solid rgba(99,102,241,0.08);border-radius:12px;
  margin-bottom:20px;overflow:hidden;
  box-shadow:0 1px 3px rgba(15,23,42,0.04),0 4px 12px rgba(99,102,241,0.06)
}
.kcard-bar{height:5px;background:linear-gradient(90deg,#3b82f6,#8b5cf6,#ec4899)}
.kcard-header{padding:14px 20px;display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid #f1f5f9}
.kcard-id{font-size:11px;color:#64748b;font-family:'JetBrains Mono',monospace;font-weight:500}
.kcard-badge{
  background:linear-gradient(135deg,#ddd6fe,#c4b5fd);color:#4c1d95;
  padding:3px 10px;border-radius:12px;font-size:10px;font-weight:700;
  text-transform:uppercase;letter-spacing:1px
}
.kcard-scene{padding:12px 20px 6px;font-size:17px;font-weight:700;color:#0f172a}
.kcard-desc{padding:0 20px 14px;font-size:12px;color:#64748b}
.kcard-layers{padding:10px 20px 18px;display:grid;grid-template-columns:1fr 1fr;gap:12px}
.klayer{
  padding:10px 14px;background:linear-gradient(135deg,#f8fafc,#f1f5f9);
  border-radius:0 8px 8px 0;border-left:3px solid #6366f1
}
.klayer-label{font-size:11px;letter-spacing:1px;margin-bottom:6px;font-weight:700;text-transform:uppercase}
.klayer-value{font-size:13px;color:#1e293b;line-height:1.6}

/* ═══ Sandbox ═══ */
.sandbox{background:linear-gradient(180deg,#fff 0%,#f1f5f9 100%)}
.sandbox-input-box{
  background:#0f172a;color:#38bdf8;font-family:'JetBrains Mono',monospace;
  padding:14px 18px;border-radius:10px;font-size:12.5px;margin-bottom:24px;
  border-left:4px solid #38bdf8;line-height:1.6
}
.sandbox-grid{display:grid;grid-template-columns:1fr 1fr;gap:20px;margin-top:20px}
.sbox{
  border-radius:12px;padding:22px;background:#fff;border:1px solid #e2e8f0;
  position:relative;display:flex;flex-direction:column
}
.sbox.rookie{border-top:4px solid #ef4444;background:rgba(254,242,242,0.4)}
.sbox.master{border-top:4px solid #10b981;background:rgba(240,253,250,0.4);box-shadow:0 4px 24px rgba(16,185,129,0.08)}
.sbox-badge{position:absolute;top:-12px;right:20px;padding:2px 10px;border-radius:12px;font-size:10px;font-weight:700;text-transform:uppercase}
.rookie .sbox-badge{background:#fee2e2;color:#991b1b}
.master .sbox-badge{background:#d1fae5;color:#065f46}
.sbox-title{font-size:14px;font-weight:700;color:#0f172a;margin-bottom:12px}
.sbox-text{font-size:13px;color:#475569;line-height:1.7;margin-bottom:16px}
.master .sbox-text{color:#1e1b4b;font-weight:500}
.sbox-feedback{font-size:12px;padding:12px 14px;border-radius:8px;margin-top:auto;line-height:1.6}
.rookie .sbox-feedback{background:#fff5f5;color:#c53030;border:1px solid #feb2b2}
.master .sbox-feedback{background:#f0fdf4;color:#15803d;border:1px solid #bbf7d0}

/* ═══ Impact ═══ */
.impact{background:linear-gradient(180deg,#fffbeb 0%,#fff 60%,#fdf4ff 100%);text-align:center}
.impact-title{
  font-size:28px;font-weight:800;margin:10px 0 8px;
  background:linear-gradient(135deg,#b45309,#be185d);
  -webkit-background-clip:text;background-clip:text;-webkit-text-fill-color:transparent;
  letter-spacing:-0.5px
}
.impact-subtitle{font-size:13px;color:#92400e;font-style:italic;margin-bottom:24px}
.pill{
  display:inline-flex;align-items:center;gap:12px;padding:10px 22px;border-radius:24px;
  font-size:13px;font-weight:700;margin-bottom:24px;
  box-shadow:0 4px 12px rgba(99,102,241,0.2);letter-spacing:0.5px;
  background:linear-gradient(135deg,#6366f1,#8b5cf6);color:#fff
}
.pill-num{font-size:11px;padding:2px 8px;background:rgba(255,255,255,0.3);border-radius:12px;font-weight:700;letter-spacing:1px}
.impact-grid{display:grid;grid-template-columns:1fr 1fr;gap:14px;text-align:left;margin:24px 0}
.impact-card{
  background:linear-gradient(135deg,rgba(255,255,255,0.95),rgba(245,243,255,0.7));
  padding:16px 20px;border-radius:12px;border:1px solid rgba(99,102,241,0.1);
  box-shadow:0 1px 3px rgba(15,23,42,0.04),0 4px 12px rgba(99,102,241,0.05)
}
.impact-card:hover{transform:translateY(-2px)}
.impact-card-title{font-size:13px;font-weight:700;letter-spacing:0.5px;margin-bottom:6px}
.impact-card-body{font-size:13px;color:#1e1b4b;line-height:1.7;font-style:italic}
.impact-divider{display:flex;align-items:center;gap:16px;margin:32px 0}
.impact-divider-line{flex:1;height:1px;background:linear-gradient(90deg,transparent,rgba(99,102,241,0.3),transparent)}
.impact-divider-text{font-size:13px;color:#64748b;font-style:italic;white-space:nowrap;font-weight:500}
.impact-rays{text-align:left}
.impact-ray{
  background:#fff;padding:14px 18px;border-radius:10px;margin-bottom:10px;
  border-left:4px solid #3b82f6;box-shadow:0 1px 3px rgba(15,23,42,0.04);transition:transform .15s
}
.impact-ray:hover{transform:translateX(4px)}
.impact-ray-title{font-size:13px;font-weight:700;color:#1e40af}
.impact-ray-desc{font-size:12px;color:#475569;line-height:1.6;margin-top:4px}

/* ═══ Closing ═══ */
.closing{
  background:linear-gradient(135deg,#fdf4ff 0%,#ede9fe 30%,#e0f2fe 70%,#faf5ff 100%);
  min-height:580px;text-align:center;display:flex;flex-direction:column;
  justify-content:space-between;position:relative;overflow:hidden
}
.closing::before{
  content:"";position:absolute;top:-100px;left:-100px;width:280px;height:280px;
  border-radius:50%;background:radial-gradient(circle,rgba(139,92,246,0.18),transparent 70%);pointer-events:none
}
.closing-eyebrow{
  font-size:11px;font-weight:700;
  background:linear-gradient(135deg,#6366f1,#ec4899);
  -webkit-background-clip:text;background-clip:text;-webkit-text-fill-color:transparent;
  letter-spacing:3px;text-transform:uppercase;position:relative;z-index:1
}
.closing-divider{width:50px;height:3px;background:linear-gradient(90deg,#6366f1,#ec4899);border-radius:2px;margin:14px auto 24px;position:relative;z-index:1}
.closing-tagline{font-size:14px;color:#64748b;font-style:italic;margin-bottom:18px;position:relative;z-index:1}
.closing-big{
  font-size:28px;font-weight:800;line-height:1.5;letter-spacing:0.5px;margin-bottom:28px;
  background:linear-gradient(135deg,#6366f1,#8b5cf6,#ec4899);
  -webkit-background-clip:text;background-clip:text;-webkit-text-fill-color:transparent;
  position:relative;z-index:1
}
.closing-footer{
  border-top:1px dashed rgba(99,102,241,0.25);padding-top:20px;margin-top:28px;
  font-size:15px;font-weight:700;letter-spacing:2px;
  background:linear-gradient(135deg,#6366f1,#ec4899);
  -webkit-background-clip:text;background-clip:text;-webkit-text-fill-color:transparent;
  position:relative;z-index:1
}

/* ═══ Timeline ═══ */
.tl{position:relative;padding-left:36px;margin:20px 0}
.tl::before{content:'';position:absolute;left:11px;top:6px;bottom:6px;width:2px;background:linear-gradient(180deg,#6366f1,#ec4899);border-radius:1px}
.tl-item{position:relative;margin-bottom:20px}
.tl-item:last-child{margin-bottom:0}
.tl-dot{position:absolute;left:-25px;top:4px;width:10px;height:10px;border-radius:50%;background:#6366f1;border:2px solid #ddd6fe}
.tl-item:last-child .tl-dot{background:#ec4899;border-color:#fce7f3}
.tl-item h4{font-size:15px;font-weight:700;color:#1e1b4b;margin-bottom:4px}
.tl-item p{font-size:13px;color:#64748b;line-height:1.7}

/* ═══ FAQ ═══ */
.fq{padding:16px 0;border-bottom:1px solid #f1f5f9}
.fq:last-child{border-bottom:none}
.fq-q{font-size:13px;font-weight:700;color:#0f172a;margin-bottom:6px;padding-left:16px;position:relative}
.fq-q::before{content:'';position:absolute;left:0;top:6px;width:6px;height:6px;border-radius:1px;background:#f59e0b}
.fq-a{font-size:13px;color:#475569;padding-left:16px;line-height:1.7}

/* ═══ Footer ═══ */
.ft{text-align:center;padding:40px 24px;color:#94a3B8;font-size:11px;letter-spacing:0.04em}

@media print{
  .nav{display:none}
  .page{page-break-after:always;margin:0;border-radius:0;box-shadow:none;border:none}
  body{background:#fff}
  .container{padding:0;max-width:100%}
}
@media(max-width:640px){
  .page{padding:28px 22px}
  .stat-grid,.kcard-layers,.impact-grid,.sandbox-grid,.sandbox-grid{grid-template-columns:1fr}
  .nav{flex-direction:column;gap:10px}
  .cover-name{font-size:28px}
}
</style>
</head>
<body>

<nav class="nav">
  <div class="nav-title">萃取档案 · ${ownerName}</div>
  <div class="nav-links">
    <a href="#cover">封面</a>
    <a href="#dashboard">资产对账单</a>
    <a href="#graph">知识图谱</a>
    <a href="#cards">知识卡精选</a>
    <a href="#sandbox">智能体手术台</a>
    <a href="#impact">影响辐射</a>
  </div>
</nav>

<div class="container">

<!-- ═══════ 封面 ═══════ -->
<section id="cover" class="page cover">
  <div class="cover-top">
    <div class="cover-eyebrow">EXTRACTION DOSSIER · AI 经验萃取</div>
    <div class="cover-divider"></div>
    <div class="cover-tagline">让我帮您，把每一次成交变成可复用的智慧</div>
    <div class="cover-name">${ownerName}</div>
    <#if caseSummary?? && caseSummary.customerIndustry??>
    <div class="cover-role">${caseSummary.customerIndustry!''} · ${domainName!'销售'}专家</div>
    </#if>
  </div>
  <#if oneliner??>
  <div class="cover-inscription">
    <div class="cover-inscription-label">CORE INSCRIPTION / 核心铭文</div>
    <div class="cover-inscription-text">"${oneliner}"</div>
  </div>
  </#if>
  <div class="cover-footer">
    <span>${date}</span>
    <span>by AI-Extraction-Engine</span>
  </div>
</section>

<!-- ═══════ 资产对账单 ═══════ -->
<section id="dashboard" class="page dashboard">
  <div class="sec-eyebrow">01 / 您的数字经验资产</div>
  <h2 class="sec-title">您的经验，已经开始变成数字资产</h2>
  <div class="sec-subtitle">
    刚才这场对话，不只是一次交流。更像是一次"显影"。<br>
    您过去很多凭感觉做出的判断，正在被我们一起拆解出来、看清楚、结构化。
  </div>
  <div class="stat-grid">
    <div class="stat-card" style="border-left:4px solid #3b82f6">
      <div class="stat-label">经验颗粒</div>
      <div class="stat-number" style="color:#3b82f6">${grainCount}</div>
      <div class="stat-desc">我们从您的真实经历中，识别出了 ${grainCount} 个可复用经验单元。它们可以变成案例、课程、手册、陪练脚本和 AI 助手知识库。</div>
    </div>
    <div class="stat-card" style="border-left:4px solid #10b981">
      <div class="stat-label">场景覆盖</div>
      <div class="stat-number" style="color:#10b981">${sceneCount!'—'}</div>
      <div class="stat-desc">您的经验覆盖了 ${sceneCount!'多个'} 个不同的业务场景。每一个场景都代表着一种可以被团队复用的应对策略。</div>
    </div>
    <div class="stat-card" style="border-left:4px solid #f59e0b">
      <div class="stat-label">平均质量分</div>
      <div class="stat-number" style="color:#f59e0b">${avgQualityScore!'—'}</div>
      <div class="stat-desc">经过 AI 五维验证（特异性、可复制性、因果性、差异性、可证伪性），您的经验颗粒质量稳定，可直接用于训练和实战。</div>
    </div>
    <div class="stat-card" style="border-left:4px solid #8b5cf6">
      <div class="stat-label">训练资产类别</div>
      <div class="stat-number" style="color:#8b5cf6">7 类</div>
      <div class="stat-desc">您的事件、动作、判断、反馈、信念、边界、原话，已形成一组完整材料。从这一刻开始，您的经验不再只是"您自己知道"。</div>
    </div>
  </div>
  <div class="status-bar">您的经验不只是被记录了。它正在被您重新看见，并沉淀为企业的数字化资产。</div>
</section>

<!-- ═══════ 知识图谱 ═══════ -->
<section id="graph" class="page graph-page">
  <div class="sec-eyebrow">02 / 判断模型图</div>
  <h2 class="sec-title">您的经验长这样</h2>
  <#if caseSummary?? && caseSummary.dealTarget??>
  <div class="graph-event">核心案例：${caseSummary.dealTarget!''}</div>
  </#if>
  <div class="graph-svg-wrap">
    <svg viewBox="0 0 280 280" class="graph-svg">
      <!-- 交叉轴 -->
      <line x1="140" y1="140" x2="140" y2="48" stroke="#d6d3d1" stroke-width="1.2" stroke-dasharray="3,2"/>
      <line x1="140" y1="140" x2="183" y2="140" stroke="#d6d3d1" stroke-width="1.2" stroke-dasharray="3,2"/>
      <line x1="140" y1="140" x2="140" y2="232" stroke="#d6d3d1" stroke-width="1.2" stroke-dasharray="3,2"/>
      <line x1="140" y1="140" x2="48" y2="140" stroke="#d6d3d1" stroke-width="1.2" stroke-dasharray="3,2"/>
      <line x1="140" y1="140" x2="163" y2="195" stroke="#d6d3d1" stroke-width="1.2" stroke-dasharray="3,2"/>
      <!-- 中心 -->
      <circle cx="140" cy="140" r="26" fill="#fbbf24" opacity="0.2"/>
      <circle cx="140" cy="140" r="18" fill="#f59e0b"/>
      <text x="140" y="138" text-anchor="middle" fill="#fff" font-size="6" font-weight="600">CASE</text>
      <text x="140" y="148" text-anchor="middle" fill="#fff" font-size="6"><#assign dt=(caseSummary.dealTarget!'核心案例')>${dt?substring(0,(dt?length>8)?then(8,dt?length))}</text>
      <!-- 五维节点 -->
      <circle cx="140" cy="48" r="14" fill="#3b82f6"/><text x="140" y="51" text-anchor="middle" fill="#fff" font-size="9" font-weight="600">心法</text>
      <circle cx="183" cy="140" r="14" fill="#10b981"/><text x="183" y="143" text-anchor="middle" fill="#fff" font-size="9" font-weight="600">战法</text>
      <circle cx="140" cy="232" r="14" fill="#8b5cf6"/><text x="140" y="235" text-anchor="middle" fill="#fff" font-size="9" font-weight="600">话术</text>
      <circle cx="48" cy="140" r="14" fill="#ec4899"/><text x="48" y="143" text-anchor="middle" fill="#fff" font-size="9" font-weight="600">边界</text>
      <circle cx="163" cy="195" r="14" fill="#ef4444"/><text x="163" y="198" text-anchor="middle" fill="#fff" font-size="9" font-weight="600">避坑</text>
    </svg>
  </div>
  <#if oneliner??>
  <div class="graph-inscription">
    <div class="graph-inscription-label">CORE INSCRIPTION / 核心演算法则</div>
    <div class="graph-inscription-text">"${oneliner}"</div>
  </div>
  </#if>
  <div class="graph-hint">↑ 五维知识拓扑模型 · 每个节点都是您的可复用判断资产</div>
</section>

<!-- ═══════ 知识卡精选 ═══════ -->
<#if grains?? && grains?size gt 0>
<section id="cards" class="page featured">
  <div class="sec-eyebrow">03 / 知识卡精选</div>
  <h2 class="sec-title">每个场景 · 一张代表卡</h2>
  <p class="sec-subtitle">以下是从 ${grainCount} 条经验颗粒中精选的代表性知识卡，按六层结构展开</p>

  <#list grains as g>
  <#if g?index < 3>
  <div class="kcard">
    <div class="kcard-bar"></div>
    <div class="kcard-header">
      <div class="kcard-id">GRAIN_${g?index + 1} · score ${g.qualityScore!"—"}</div>
      <span class="kcard-badge">${g.sceneTag!''}</span>
    </div>
    <div class="kcard-scene">${g.sceneDescription!''}</div>
    <div class="kcard-layers">
      <div class="klayer" style="border-left-color:#3b82f6">
        <div class="klayer-label" style="color:#3b82f6">道 · 核心信念</div>
        <div class="klayer-value">${g.expertThought!''}</div>
      </div>
      <div class="klayer" style="border-left-color:#10b981">
        <div class="klayer-label" style="color:#10b981">术 · 核心动作</div>
        <div class="klayer-value">${g.standardScript!''}</div>
      </div>
      <div class="klayer" style="border-left-color:#8b5cf6">
        <div class="klayer-label" style="color:#8b5cf6">策 · 条件策略</div>
        <div class="klayer-value">${g.applicableCondition!'（通用）'}</div>
      </div>
      <div class="klayer" style="border-left-color:#ef4444">
        <div class="klayer-label" style="color:#ef4444">坑 · 反向红线</div>
        <div class="klayer-value">${g.commonMistakes!''}</div>
      </div>
    </div>
  </div>
  </#if>
  </#list>

  <#if grains?size gt 3>
  <p class="graph-hint" style="text-align:center">+ 另有 ${grains?size - 3} 条知识卡完整收录于附录</p>
  </#if>
</section>
</#if>

<!-- ═══════ 智能体手术台 ═══════ -->
<#if grains?? && grains?size gt 0>
<#assign sampleGrain = grains[0]>
<section id="sandbox" class="page sandbox">
  <div class="sec-eyebrow">04 / 智能体手术台</div>
  <h2 class="sec-title">亲眼见证：您的智慧如何指挥 AI</h2>
  <div class="sec-subtitle">当新手遇到真实场景时，AI 在"注入您的经验前"与"注入后"的对比：</div>

  <div class="sandbox-input-box">
    🚨 典型场景：${sampleGrain.sceneDescription!''}
  </div>

  <div class="sandbox-grid">
    <div class="sbox rookie">
      <span class="sbox-badge">未注入经验</span>
      <div class="sbox-title" style="color:#c53030">基础 AI（新手模式）</div>
      <div class="sbox-text">
        ${sampleGrain.commonMistakes!''}
      </div>
      <div class="sbox-feedback">
        <strong>诊断：</strong> 这是典型的新手应对方式——缺乏具体的判断框架和话术支撑，无法在实际业务场景中有效推进。
      </div>
    </div>

    <div class="sbox master">
      <span class="sbox-badge">经验已激活</span>
      <div class="sbox-title" style="color:#0f766e">加载 ${ownerName} 经验卡</div>
      <div class="sbox-text">
        ${sampleGrain.standardScript!''}
      </div>
      <div class="sbox-feedback">
        <strong>运行成功：</strong> ${sampleGrain.expertThought!''}
      </div>
    </div>
  </div>
</section>
</#if>

<!-- ═══════ 事件过程 ═══ -->
<#if eventProcess?? && eventProcess?size gt 0>
<section id="timeline" class="page" style="background:linear-gradient(180deg,#fff 0%,#faf5ff 100%)">
  <div class="sec-eyebrow">05 / 关键事件回放</div>
  <h2 class="sec-title">每一步，拆开看</h2>
  <div class="tl">
    <#list eventProcess as stage>
    <div class="tl-item">
      <div class="tl-dot"></div>
      <h4>${stage.title}</h4>
      <p>${stage.content}</p>
    </div>
    </#list>
  </div>
</section>
</#if>

<!-- ═══════ 常见异议处理 ═══ -->
<#if faq?? && faq?size gt 0>
<section id="faq" class="page" style="background:linear-gradient(180deg,#fff 0%,#f8fafc 100%)">
  <div class="sec-eyebrow">06 / 常见异议处理</div>
  <h2 class="sec-title">销冠的回应方式</h2>
  <#list faq as f>
  <div class="fq"><div class="fq-q">${f.question}</div><div class="fq-a">${f.answer}</div></div>
  </#list>
</section>
</#if>

<!-- ═══════ 影响辐射 ═══════ -->
<section id="impact" class="page impact">
  <div class="sec-eyebrow">07 / 影响辐射</div>
  <h2 class="impact-title">先确认您不可替代，再让您的智慧放大</h2>
  <div class="impact-subtitle">不是封在档案柜里 — 而是先赋能您自己，再自然辐射到他人</div>

  <div class="pill"><span class="pill-num">01</span><span>先赋能自己 · AI 离不开您</span></div>
  <div class="impact-grid">
    <#if strategies?? && strategies?size gt 0>
    <#list strategies as s>
    <#if s?index < 4>
    <div class="impact-card" style="border-left:3px solid #8b5cf6">
      <div class="impact-card-title" style="color:#8b5cf6">${s.name}</div>
      <div class="impact-card-body">${s.principle}</div>
    </div>
    </#if>
    </#list>
    </#if>
  </div>

  <div class="impact-divider">
    <div class="impact-divider-line"></div>
    <div class="impact-divider-text">您脑子里的判断，值得被更多新人复制</div>
    <div class="impact-divider-line"></div>
  </div>

  <div class="pill" style="background:linear-gradient(135deg,#f59e0b,#ec4899)"><span class="pill-num">02</span><span>再影响他人 · AI 替您走出去</span></div>
  <div class="impact-rays">
    <#if tactics?? && tactics?size gt 0>
    <#list tactics as t>
    <#if t?index < 4>
    <div class="impact-ray" style="border-left-color:#3b82f6">
      <div class="impact-ray-title" style="color:#3b82f6">→ ${t.name}</div>
      <div class="impact-ray-desc">${t.method}</div>
    </div>
    </#if>
    </#list>
    </#if>
  </div>
</section>

<!-- ═══════ 踩坑提醒 ═══ -->
<#if donts?? && donts?size gt 0>
<section id="donts" class="page" style="background:linear-gradient(180deg,#fff 0%,#fef2f2 100%)">
  <div class="sec-eyebrow">08 / 新手避坑指南</div>
  <h2 class="sec-title">同样的方法，用错了也会失效</h2>
  <div class="impact-rays" style="margin-top:24px">
    <#list donts as d>
    <div class="impact-ray" style="border-left-color:#ef4444">
      <div class="impact-ray-title" style="color:#991B1B">⚠️ ${d}</div>
    </div>
    </#list>
  </div>
</section>
</#if>

<!-- ═══════ 践行者身份 ═══════ -->
<section id="closing" class="page closing">
  <div class="closing-eyebrow">EXTRACTION COMPLETE</div>
  <div class="closing-divider"></div>
  <div class="closing-tagline">AI 不是来替代您，是让您的智慧走得更远</div>
  <h2 class="closing-big">
    把自己的隐性智慧<br>
    变成 AI 可用的<br>
    经验资产
  </h2>
  <div class="closing-footer">企业数字化资产 · 奠基人</div>
</section>

<div class="ft">AI 萃取引擎自动生成 · 仅供内部培训使用 · ${date}</div>
</div>
</body>
</html>
