# MindSmith 设计系统

> 基于实际代码实现的三主题设计规范，用于整个系统建设

## 1. 主题系统

### 架构

使用 React Context (`ThemeProvider`) + CSS 变量切换。三主题通过 `document.documentElement` 上的 class（`.theme-dark`、`.theme-claude`）切换 CSS 变量值。Light 主题为默认，不加 class。

```tsx
// ThemeProvider 位于 src/lib/theme.tsx
// 主题切换组件位于 src/components/ui/ThemeToggle.tsx

import { useTheme, type Theme } from '@/lib/theme';

const { theme, setTheme } = useTheme();
// theme: 'light' | 'dark' | 'claude'
// setTheme(t) 会同步更新 document.documentElement.classList 和 localStorage
```

主题持久化 key：`mindsmith-theme`（localStorage）。

### 三主题 CSS 变量定义

#### 基础色

| 变量 | Light（默认） | Dark（专注模式） | Claude（人文温度） |
|------|-------------|----------------|-------------------|
| `--bg` | `#ffffff` | `#0f172a` | `#fdf8f6` |
| `--surface` | `#f8fafc` | `#111c31` | `#fff7ed` |
| `--surface-2` | `#ffffff` | `#162235` | `#fffaf3` |
| `--card` | `rgba(255,255,255,0.88)` | `rgba(30,41,59,0.88)` | `rgba(255,247,237,0.92)` |

#### 文字色

| 变量 | Light | Dark | Claude |
|------|-------|------|--------|
| `--foreground` | `#1e293b` | `#e2e8f0` | `#292524` |
| `--muted-foreground` | `#64748b` | `#94a3b8` | `#78716c` |
| `--muted-foreground-2` | `#94a3b8` | `#64748b` | `#a8a29e` |

#### 边框

| 变量 | Light | Dark | Claude |
|------|-------|------|--------|
| `--border` | `#e2e8f0` | `#334155` | `#fed7aa` |
| `--border-strong` | `#cbd5e1` | `#475569` | `#fdba74` |

#### 主色

| 变量 | Light | Dark | Claude |
|------|-------|------|--------|
| `--primary` | `#2563eb` | `#60a5fa` | `#d97706` |
| `--primary-hover` | `#1d4ed8` | `#3b82f6` | `#b45309` |
| `--primary-light` | `rgba(37,99,235,0.08)` | `rgba(96,165,250,0.12)` | `rgba(217,119,6,0.08)` |
| `--gradient-primary` | `linear-gradient(135deg, #06b6d4 0%, #3b82f6 100%)` | `linear-gradient(135deg, #38bdf8 0%, #60a5fa 100%)` | `linear-gradient(135deg, #f59e0b 0%, #fb7185 100%)` |

### 语义色

每个主题下都定义了完整的语义色，用于错误、警告、成功等状态。

#### Light

```css
--success: #16a34a;
--success-bg: #dcfce7;
--warning: #d97706;
--warning-bg: #fef3c7;
--warning-text: #92400e;
--danger: #dc2626;
--danger-bg: #fef2f2;
--danger-hover: #fecaca;
--gold: #c8a45c;
--navy: #1e293b;
--navy-light: #334155;
--orange: #ea580c;
```

#### Dark

```css
--success: #4ade80;
--success-bg: rgba(74, 222, 128, 0.12);
--warning: #fbbf24;
--warning-bg: rgba(251, 191, 36, 0.12);
--warning-text: #fde68a;
--danger: #f87171;
--danger-bg: rgba(248, 113, 113, 0.12);
--danger-hover: rgba(248, 113, 113, 0.20);
--gold: #e2b96f;
--navy: #334155;
--navy-light: #475569;
--orange: #fb923c;
```

#### Claude

```css
--success: #65a30d;
--success-bg: #f7fee7;
--warning: #d97706;
--warning-bg: #fffbeb;
--warning-text: #78350f;
--danger: #dc2626;
--danger-bg: #fef2f2;
--danger-hover: #fecaca;
--gold: #b8860b;
--navy: #44403c;
--navy-light: #57534e;
--orange: #c2410c;
```

## 2. 阴影系统

每个主题下阴影值不同：Light 使用 `rgba(15,23,42,...)`、Dark 使用 `rgba(0,0,0,...)` 高透明度、Claude 使用 `rgba(120,113,108,...)` 暖色调。

| 名称 | 用途 | Light 值 |
|------|------|---------|
| `--shadow-sm` | 小元素、标签 | `0 1px 2px rgba(15, 23, 42, 0.06)` |
| `--shadow-md` | 卡片默认、浮动面板 | `0 8px 20px rgba(15, 23, 42, 0.08)` |
| `--shadow-lg` | 卡片悬停 | `0 18px 44px rgba(15, 23, 42, 0.12)` |
| `--shadow-xl` | 模态框、大卡片 | `0 28px 72px rgba(15, 23, 42, 0.16)` |
| `--shadow-glow` | 输入框聚焦 | `0 0 0 4px rgba(37, 99, 235, 0.14), 0 0 30px rgba(59, 130, 246, 0.24)` |

## 3. 圆角系统

| 名称 | 值 | 用途 | Tailwind 别名 |
|------|-----|------|-------------|
| `--radius-sm` | `6px` | 小按钮、标签 | `rounded-control` |
| `--radius-md` | `8px` | 按钮、输入框 | `rounded-module` |
| `--radius-lg` | `12px` | 标准卡片 | `rounded-card` |
| `--radius-xl` | `16px` | 特色卡片 | — |
| `--radius-2xl` | `24px` | Hero 区域、大卡片 | — |
| `--radius-pill` | `999px` | 胶囊标签 | `rounded-pill` |

## 4. 按钮组件

### 基础样式

```css
.btn {
  height: 40px;
  padding: 0 16px;
  border-radius: var(--radius-md);
  font-weight: 600;
  font-size: 14px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  transition: transform .22s ease, box-shadow .22s ease;
}
.btn:hover { transform: translateY(-1px); }
.btn:disabled { opacity: .5; cursor: not-allowed; transform: none; }
```

### 变体

| 变体 | class | 用途 | CSS |
|------|-------|------|-----|
| Primary | `.btn-primary` | 主操作 | `background: var(--gradient-primary); color: white;` hover 时 `box-shadow: var(--shadow-md)` |
| Secondary | `.btn-secondary` | 次操作 | `background: var(--surface-2); border: 1px solid var(--border); color: var(--muted-foreground);` |
| Ghost | `.btn-ghost` | 轻操作 | `background: transparent; color: var(--muted-foreground);` hover 时 `background: var(--primary-light); color: var(--primary);` |
| Large | `.btn-lg` | 大按钮 | `height: 48px; padding: 0 24px; font-size: 16px;` 叠加以上变体 |

## 5. 卡片组件

### 标准卡片

```css
.card {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  padding: 22px;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  transition: transform .22s ease, box-shadow .22s ease;
}
.card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
}
```

### 小卡片

```css
.card-sm {
  /* 与 .card 相同，但 padding: 16px，无 hover 动效 */
}
```

### 特色卡片

```css
.feature-card {
  background: var(--card);
  border-top: 3px solid var(--primary);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-md);
  padding: 32px;
}
```

## 6. 输入框组件

```css
.input {
  height: 44px;
  border-radius: var(--radius-md);
  border: 1px solid var(--border);
  background: var(--surface-2);
  padding: 0 14px;
  font-size: 14px;
  outline: none;
  transition: box-shadow .2s ease, border-color .2s ease;
}
.input:focus {
  border-color: var(--primary);
  box-shadow: var(--shadow-glow);
}
```

## 7. 标签组件

### Pill 标签

```css
.pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 700;
  color: var(--muted-foreground);
  border: 1px solid var(--border);
  padding: 6px 10px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--surface-2) 85%, transparent);
}
```

### Eyebrow 标签

```css
.eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--primary) 8%, var(--surface-2));
  color: var(--primary);
  font-size: 12px;
  font-weight: 700;
  border: 1px solid color-mix(in srgb, var(--primary) 18%, var(--border));
}
```

## 8. 图标芯片

```css
.icon-chip {
  width: 44px;
  height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 14px;
  background: color-mix(in srgb, var(--primary) 10%, var(--surface));
  color: var(--primary);
  font-weight: 800;
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--primary) 18%, transparent);
}
```

## 9. 消息气泡

### AI 消息

```css
.message-ai {
  background: var(--surface);
  border: 1px solid color-mix(in srgb, var(--primary) 16%, var(--border));
  border-radius: 16px;
  border-bottom-left-radius: 6px;
  padding: 12px 16px;
  font-size: 14px;
}
```

### 用户消息

```css
.message-user {
  background: var(--surface-2);
  border: 1px solid color-mix(in srgb, var(--primary) 18%, var(--border));
  border-radius: 16px;
  border-bottom-right-radius: 6px;
  padding: 12px 16px;
  font-size: 14px;
}
```

## 10. 进度指示器

### 进度条（Progress Strip）

```css
.progress-strip {
  display: flex;
  gap: 8px;
}
.progress-strip span {
  height: 8px;
  flex: 1;
  border-radius: 999px;
  background: color-mix(in srgb, var(--muted-foreground-2) 16%, transparent);
  position: relative;
  overflow: hidden;
}
.progress-strip span.active::before {
  content: "";
  position: absolute;
  inset: 0;
  background: var(--gradient-primary);
}
```

### 步骤指示器（Step Dot）

```css
.step-dot {
  width: 32px; height: 32px;
  border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  font-size: 14px; font-weight: 600; flex-shrink: 0;
}
.step-dot.done    { background: var(--success); color: white; }
.step-dot.active  { background: var(--gradient-primary); color: white; }
.step-dot.pending { background: transparent; border: 2px solid var(--border); color: var(--muted-foreground-2); }

.step-bar {
  display: flex; align-items: center; gap: 0;
  background: var(--card);
  border-radius: var(--radius-lg);
  padding: 16px;
}
```

## 11. K2J 金字塔层级

```css
.layer {
  position: relative;
  min-height: 66px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-weight: 700;
  box-shadow: var(--shadow-md);
  cursor: pointer;
  transition: transform .22s ease;
}
.layer:hover { transform: translateY(-2px) scale(1.01); }

/* 各层渐变 — 装饰性色彩，不跟随主题切换 */
.layer-1 { width: 36%; background: linear-gradient(135deg, #1d4ed8, #3b82f6); }
.layer-2 { width: 48%; background: linear-gradient(135deg, #2563eb, #06b6d4); }
.layer-3 { width: 60%; background: linear-gradient(135deg, #0ea5e9, #14b8a6); }
.layer-4 { width: 72%; background: linear-gradient(135deg, #7c3aed, #3b82f6); }
.layer-5 { width: 84%; background: linear-gradient(135deg, #8b5cf6, #06b6d4); }
.layer-6 { width: 96%; background: linear-gradient(135deg, #ec4899, #8b5cf6); }
```

## 12. 字体规范

| 名称 | Tailwind class | 大小 | 字重 | 行高 |
|------|---------------|------|------|------|
| H1 | `text-h1` | 28px | 600 | 1.5 |
| H2 | `text-h2` | 20px | 600 | 1.5 |
| H3 | `text-h3` | 16px | 500 | 1.5 |
| Body | `text-body` | 14px | 400 | 1.5 |
| Caption | `text-caption` | 12px | 400 | 1.5 |

字体系列：`Inter`, `Noto Sans SC`, `system-ui`, `sans-serif`

## 13. Tailwind CSS 集成

### 配置架构

CSS 变量通过 `tailwind.config.js` 映射为 Tailwind 工具类。所有颜色、阴影、圆角值都是 `var(--xxx)` 引用，实现主题切换时自动跟随。

```js
// tailwind.config.js theme.extend 中的关键映射
colors: {
  bg: 'var(--bg)',
  surface: 'var(--surface)',
  foreground: 'var(--foreground)',
  primary: 'var(--primary)',
  // ... (完整见 tailwind.config.js)
},
boxShadow: {
  sm: 'var(--shadow-sm)',
  card: 'var(--shadow-sm)',   // 兼容别名
  float: 'var(--shadow-md)',  // 兼容别名
},
borderRadius: {
  sm: 'var(--radius-sm)',
  card: 'var(--radius-lg)',    // 兼容别名
  module: 'var(--radius-md)',  // 兼容别名
  control: 'var(--radius-sm)', // 兼容别名
  pill: 'var(--radius-pill)',  // 兼容别名
},
```

### 使用方式

```tsx
// 组件中使用 Tailwind 语义类（自动跟随主题切换）
<div className="bg-surface text-foreground">
  <button className="btn btn-primary">主要操作</button>
  <div className="card">
    <div className="icon-chip">A</div>
    <h3 className="text-h3 text-foreground">卡片标题</h3>
  </div>
</div>

// 或直接使用 CSS 变量（非 Tailwind 场景）
<div style={{ background: 'var(--card)', color: 'var(--foreground)' }}>
```

### 升级后的语义色使用

```tsx
// 错误/警告/成功状态
<span className="text-danger bg-danger-bg">错误</span>
<span className="text-warning bg-warning-bg">警告</span>
<span className="text-success bg-success-bg">成功</span>

// 装饰色
<span className="text-gold">金牌</span>
<div className="bg-navy text-white">深色面板</div>
<span className="text-orange">重要</span>
```

## 14. 响应式断点

| 断点 | 宽度 | 行为 |
|------|------|------|
| Desktop | > 1100px | 完整双栏布局 |
| Tablet | 768px - 1100px | 单栏，组件 2 列 |
| Mobile | < 768px | 单栏，组件 1 列 |

## 15. 容器规范

```css
.container {
  width: min(1400px, calc(100% - 48px));
  margin: 0 auto;
}
```
