# 分身聊天 — 逐文件逐行 UI 改动明细

> 2026-07-27 · 架构：定时任务预聚合 → Skill 表字段 → 接口直接读（零额外查询）
> 已通过架构审查，P0/P1/P2 问题全部修入方案。

---

## 架构决策

**不做**：接口里实时查 `conversation_stats` + `feedback_log` 聚合 → 每次请求跑 GROUP BY，广场页 9 个分身 = 9 次全表扫描，不可扩展。

**改为**：
```
conversation_stats / feedback_log (每次对话写入)
        │
        ▼  每 5 分钟定时任务
┌─────────────────────────────┐
│ SkillStatsScheduler         │
│ 批量聚合 → 写入 skill 表字段 │
└─────────────────────────────┘
        │
        ▼  接口直接读列（无 JOIN、无 GROUP BY）
GET /skills/{id}/detail  →  skill.conversation_count
GET /skills/list          →  skill.conversation_count（已在分页结果中）
GET /public/share/{code}  →  skill.conversation_count
```

---

## 架构审查记录（2026-07-27）

| 级别 | 问题 | 修复方式 |
|------|------|---------|
| P0 | `batchStatsOverview` 不过滤 `is_test`，管理员测试对话会算进用户看到的统计 | 新增 `batchStatsOverviewExcludeTest` 查询方法 |
| P0 | `SkillRepository.findByStatusIn` 不存在，Scheduler 编译不过 | 新增方法 |
| P1 | Migration `DEFAULT 0` 对已有行不生效，已有分身读出来是 NULL | Migration 末尾加显式 `UPDATE SET = 0 WHERE IS NULL` |
| P1 | Scheduler 一次加载全部技能到内存，10 万+分身会 OOM | 加 TODO 注释，当前数据量无风险 |
| P1 | Scheduler 无重叠执行防护，DB 慢查询可能导致任务积压 | 加 `AtomicBoolean` 简易防重入 |
| P2 | 颗粒数已有实时索引查询，无需缓存到 skill 表 | Skill 实体只加 4 个互动统计字段，不加 grain 缓存 |
| P2 | 缺少消息总数统计 | 标 TODO，Phase 2 从 `skill_message` 表聚合 |

---

## 改动总览

| # | 文件 | 改动类型 | 说明 |
|---|------|---------|------|
| 1 | `backend/.../model/Skill.java` | 加 4 字段 | conversationCount / userCount / satisfactionRate / lastActiveAt |
| 2 | `db/migration/V30__skill_stats_columns.sql` | **新建** | DDL 加列 + 已有行显式填充默认值 |
| 3 | `backend/.../scheduler/SkillStatsScheduler.java` | **新建** | 定时聚合 + 防重入 + 过滤 is_test |
| 4 | `backend/.../repository/SkillRepository.java` | 加方法 | findByStatusIn |
| 5 | `backend/.../repository/ConversationStatsRepository.java` | 加方法 | batchStatsOverviewExcludeTest（过滤 is_test） |
| 6 | `backend/.../service/SkillService.java` | 逻辑改 | getSkillDetail/listAllSkills 直接读 Skill 字段 |
| 7 | `backend/.../service/ShareService.java` | 逻辑改 | getShareInfo 从 Skill 读 stats |
| 8 | `backend/.../dto/ShareInfoResponse.java` | 加字段 | 追加 stats 对象 |
| 9 | `backend/.../controller/PublicController.java` | 逻辑改 | listPublicSkills 从 Skill 读 stats |
| 10 | `packages/shared-ui/.../TrustBadge.tsx` | 改 props | 接受动态数据，有则显示真实数字 |
| 11 | `packages/shared-ui/.../StatBadge.tsx` | **新建** | 三端统一的统计数值组件 |
| 12 | `packages/shared-ui/src/index.ts` | 导出 | 导出 StatBadge |
| 13 | `frontend/src/lib/api/skill.ts` | 加类型 | SkillInfo 加 stats 字段 |
| 14 | `frontend/src/lib/api/c.ts` | 加类型 | ShareInfo 加 stats 字段 |
| 15 | `enterprise/src/lib/api/skill.ts` | 加类型 | SkillDetail/PublicSkillInfo 加 stats 字段 |
| 16 | `frontend/src/app/skills/page.tsx` | UI 改 | 分身广场卡片 redesign |
| 17 | `frontend/src/app/skill/[id]/page.tsx` | UI 改 | 分身详情 Hero redesign |
| 18 | `frontend/src/app/s/[code]/ShareLanding.tsx` | UI 改 | H5 分享落地页 redesign |
| 19 | `frontend/src/app/s/[code]/MobileChatShell.tsx` | UI 改 | H5 聊天壳 Hero redesign |
| 20 | `enterprise/.../chat/ChatEntry.tsx` | UI 改 | 企业端 Hero redesign |
| 21 | `enterprise/.../discover/ExpertRow.tsx` | UI 改 | 企业端发现页卡片 redesign |

---

## 改动 1：Skill 实体 — 加 4 个互动统计字段

**文件：** `backend/src/main/java/com/aiextract/model/Skill.java`

在第 172 行 `talkConfig` 字段之后、第 177 行 `createdAt` 之前插入：

```java
// ═══════════════════════════════════════════════════════════
// 互动统计（SkillStatsScheduler 每 5 分钟批量聚合写入，API 直接读列）
// 不缓存 grain_count —— 颗粒数已有实时索引查询，且审核流程中会变化，缓存反而不一致
// ═══════════════════════════════════════════════════════════

/** 近30天不重复对话次数（来自 conversation_stats，已过滤 is_test） */
@Column(name = "conversation_count")
private Integer conversationCount;

/** 近30天不重复用户数（来自 conversation_stats，已过滤 is_test） */
@Column(name = "user_count")
private Integer userCount;

/** 满意度 0-100（feedback_log 中 up/(up+down)×100，0 表示无反馈数据） */
@Column(name = "satisfaction_rate")
private Integer satisfactionRate;

/** 最近一次对话时间（来自 conversation_stats.MAX(created_at)） */
@Column(name = "last_active_at")
private LocalDateTime lastActiveAt;
```

**设计决策：不加 `grain_count_cache` 和 `scene_count_cache`。** 理由：
- 颗粒数已在 `SkillService.listAllSkills()` 中通过 `grainRepository.countBySpaceIdIn()` 批量查（第 297 行）
- `experience_grain` 表在 `space_id` 上有索引，COUNT 查询极快
- 审核流程中颗粒状态从 active→archived 会实时变化，缓存会展示过期数据
- 只有 4 个互动统计字段需要聚合（数据源是 `conversation_stats` + `feedback_log`，不做聚合查询会很贵）

---

## 改动 2：数据库迁移

**文件：** `backend/src/main/resources/db/migration/V30__skill_stats_columns.sql`（新建）

```sql
-- 分身互动统计字段（SkillStatsScheduler 定时聚合写入）
ALTER TABLE skill
    ADD COLUMN IF NOT EXISTS conversation_count INTEGER DEFAULT 0,
    ADD COLUMN IF NOT EXISTS user_count INTEGER DEFAULT 0,
    ADD COLUMN IF NOT EXISTS satisfaction_rate INTEGER DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_active_at TIMESTAMP;

-- PostgreSQL 的 ALTER TABLE ADD COLUMN DEFAULT 只对新 INSERT 的行生效，
-- 已有行仍然是 NULL。显式填充默认值，确保已有分身的字段不为 NULL。
UPDATE skill SET conversation_count = 0 WHERE conversation_count IS NULL;
UPDATE skill SET user_count = 0 WHERE user_count IS NULL;
UPDATE skill SET satisfaction_rate = 0 WHERE satisfaction_rate IS NULL;
```

---

## 改动 3：ConversationStatsRepository — 新增排除测试数据的查询

**文件：** `backend/src/main/java/com/aiextract/repository/ConversationStatsRepository.java`

在 `batchStatsOverview` 方法之后（第 59 行之后）追加：

```java
/**
 * 批量互动统计（排除 is_test）。
 * SkillStatsScheduler 使用此方法，确保管理员测试对话不污染用户侧统计。
 * 原 batchStatsOverview 保留给 AdminInsightService（管理后台可能需要看全量）。
 *
 * @param skillIds 分身 ID 列表
 * @param start    统计窗口起始
 * @param end      统计窗口结束
 * @return [skillId, conversationCount, userCount, ragHigh, ragRef, ragNone, lastActive]
 */
@Query("SELECT cs.skillId, COUNT(DISTINCT cs.conversationId), COUNT(DISTINCT cs.userId), "
     + "COALESCE(SUM(cs.ragHighCount),0), COALESCE(SUM(cs.ragRefCount),0), "
     + "COALESCE(SUM(cs.ragNoneCount),0), MAX(cs.createdAt) "
     + "FROM ConversationStats cs "
     + "WHERE cs.skillId IN :skillIds AND cs.isTest = false "
     + "AND cs.createdAt BETWEEN :start AND :end "
     + "GROUP BY cs.skillId")
List<Object[]> batchStatsOverviewExcludeTest(
    @Param("skillIds") List<UUID> skillIds,
    @Param("start") LocalDateTime start,
    @Param("end") LocalDateTime end);
```

**为什么新增方法而不是改原方法：** `AdminInsightService.getGlobalOverview()` 使用原 `batchStatsOverview` 做管理后台的"分身健康度"报表，管理员可能需要看到包含测试数据的全貌。两个场景数据口径不同——用户侧排除 is_test，管理侧保留全量。

---

## 改动 4：SkillRepository — 加 findByStatusIn

**文件：** `backend/src/main/java/com/aiextract/repository/SkillRepository.java`

在第 61 行 `findByStatus(String status)` 之后追加：

```java
/** 按状态列表批量查询 — SkillStatsScheduler 用 */
List<Skill> findByStatusIn(List<String> statuses);
```

Spring Data JPA 自动解析方法名，无需写 JPQL。

---

## 改动 5：SkillStatsScheduler — 定时聚合（完整版）

**文件：** `backend/src/main/java/com/aiextract/scheduler/SkillStatsScheduler.java`（新建）

参照现有 `DataCleanupScheduler` 模式，整合所有架构审查修复点：

```java
package com.aiextract.scheduler;

import com.aiextract.model.Skill;
import com.aiextract.repository.ConversationStatsRepository;
import com.aiextract.repository.FeedbackLogRepository;
import com.aiextract.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 分身互动统计定时聚合 — 每 5 分钟从 conversation_stats + feedback_log
 * 批量计算并写入 skill 表字段，API 路径直接读列，零 GROUP BY 开销。
 *
 * <p>防重入：AtomicBoolean 保证同一时刻只有一个任务在执行。
 * 如果上次执行因 DB 慢查询超过 5 分钟，本次直接跳过（不排队积压）。</p>
 *
 * <p>数据口径：使用 batchStatsOverviewExcludeTest 排除 is_test=true 的管理员测试数据，
 * 确保用户侧展示的"128 次对话""94% 满意"不包含内部测试。</p>
 *
 * <p>TODO: 当分身数量超过 10000 时，改为分页批量处理（每批 500），避免一次性加载过多实体到内存。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-27
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillStatsScheduler {

    private final SkillRepository skillRepository;
    private final ConversationStatsRepository conversationStatsRepository;
    private final FeedbackLogRepository feedbackLogRepository;

    /** 防重入：true 表示当前有任务在执行 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 每 5 分钟执行一次 */
    @Transactional(rollbackFor = Exception.class)
    @Scheduled(cron = "0 */5 * * * ?")
    public void refreshSkillStats() {
        if (!running.compareAndSet(false, true)) {
            log.debug("上一次统计聚合尚未完成，跳过本次执行");
            return;
        }
        try {
            doRefresh();
        } catch (Exception e) {
            log.error("刷新分身互动统计失败", e);
        } finally {
            running.set(false);
        }
    }

    private void doRefresh() {
        // 只刷新有意义的 skill（published + reviewing，跳过 discarded 和 generating）
        List<Skill> skills = skillRepository.findByStatusIn(
                List.of("published", "reviewing"));
        if (skills.isEmpty()) return;

        List<UUID> skillIds = skills.stream().map(Skill::getId).toList();
        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        LocalDateTime now = LocalDateTime.now();

        // ① 批量 conversation stats — 使用 excludeTest 版本，过滤管理员测试数据
        List<Object[]> batchStats = conversationStatsRepository.batchStatsOverviewExcludeTest(
                skillIds, thirtyDaysAgo, now);
        Map<UUID, Long> convMap = new HashMap<>();
        Map<UUID, Long> userMap = new HashMap<>();
        Map<UUID, LocalDateTime> lastActiveMap = new HashMap<>();
        for (Object[] row : batchStats) {
            UUID sid = (UUID) row[0];
            convMap.put(sid, num(row[1]));
            userMap.put(sid, num(row[2]));
            if (row[6] != null) lastActiveMap.put(sid, (LocalDateTime) row[6]);
        }

        // ② 批量 satisfaction stats
        List<Object[]> batchSat = feedbackLogRepository.batchSatisfactionStats(skillIds);
        Map<UUID, Integer> satMap = new HashMap<>();
        for (Object[] row : batchSat) {
            UUID sid = (UUID) row[0];
            long up = num(row[1]);
            long total = num(row[2]);
            satMap.put(sid, total > 0 ? (int) Math.round((double) up / total * 100) : 0);
        }

        // ③ 写入 skill 字段 — 只 UPDATE 有变更的行
        int updated = 0;
        for (Skill skill : skills) {
            Long conv = convMap.getOrDefault(skill.getId(), 0L);
            Long users = userMap.getOrDefault(skill.getId(), 0L);
            Integer sat = satMap.getOrDefault(skill.getId(), 0);
            LocalDateTime lastActive = lastActiveMap.get(skill.getId());

            boolean changed = false;
            if (!Objects.equals(skill.getConversationCount(), conv.intValue())) {
                skill.setConversationCount(conv.intValue()); changed = true;
            }
            if (!Objects.equals(skill.getUserCount(), users.intValue())) {
                skill.setUserCount(users.intValue()); changed = true;
            }
            if (!Objects.equals(skill.getSatisfactionRate(), sat)) {
                skill.setSatisfactionRate(sat); changed = true;
            }
            if (!Objects.equals(skill.getLastActiveAt(), lastActive)) {
                skill.setLastActiveAt(lastActive); changed = true;
            }
            if (changed) updated++;
        }
        // JPA 自动 flush — 只 UPDATE 有变更的实体

        if (updated > 0) {
            log.info("刷新分身互动统计完成: {} 个分身有变更, 共 {} 个", updated, skills.size());
        }
    }

    private static long num(Object o) {
        return o != null ? ((Number) o).longValue() : 0L;
    }
}
```

**索引确认：**
- `conversation_stats` 已有索引 `idx_cs_skill_time ON conversation_stats(skill_id, created_at DESC)` ✅
- `feedback_log` 已有索引 `idx_fl_skill_time ON feedback_log(skill_id, created_at DESC)` ✅
- `skill` 表主键 `id` 已有索引 ✅

**预估性能：**
- 2 条批量 SQL（IN 1000 个 skillId），每次 < 200ms
- JPA dirty checking 只 UPDATE 有变更的行（通常 < 10%）
- 每 5 分钟一次，对数据库几乎零压力
- 索引覆盖全部 WHERE + GROUP BY 列，无全表扫描

---

## 改动 6：SkillService — 接口直接读 Skill 字段

**文件：** `backend/src/main/java/com/aiextract/service/SkillService.java`

不需要注入新的 Repository。现有构造函数保持不变。

### 6a. getSkillDetail() — 追加 stats（第 983 行之前）

```java
// ── 互动统计（SkillStatsScheduler 定时聚合写入 skill 表，直接读列） ──
Map<String, Object> stats = new LinkedHashMap<>();
stats.put("conversationCount", skill.getConversationCount() != null ? skill.getConversationCount() : 0);
stats.put("userCount", skill.getUserCount() != null ? skill.getUserCount() : 0);
stats.put("satisfactionRate", skill.getSatisfactionRate() != null ? skill.getSatisfactionRate() : 0);
if (skill.getLastActiveAt() != null) {
    stats.put("lastActive", skill.getLastActiveAt().toString());
}
detail.put("stats", stats);
```

### 6b. listAllSkills() — 批量追加 stats（第 317 行之后）

在 `item.put("grainCount", ...)` 之后追加：

```java
// stats — 直接从 Skill 字段读，无需额外查询
Map<String, Object> stats = new LinkedHashMap<>();
stats.put("conversationCount", skill.getConversationCount() != null ? skill.getConversationCount() : 0);
stats.put("userCount", skill.getUserCount() != null ? skill.getUserCount() : 0);
stats.put("satisfactionRate", skill.getSatisfactionRate() != null ? skill.getSatisfactionRate() : 0);
item.put("stats", stats);
```

**无 JOIN、无 GROUP BY、无子查询。API 层改动就此结束。**

---

## 改动 7：ShareService — 从 Skill 读 stats

**文件：** `backend/src/main/java/com/aiextract/service/ShareService.java`

`getShareInfo()` 方法第 178 行已有 `Skill skill = requirePublishedSkill(share);` 拿到了实体。
在 builder（第 202 行）之前直接读取：

```java
// ── 互动统计（直接读 skill 表缓存字段） ──
Map<String, Object> stats = new LinkedHashMap<>();
stats.put("conversationCount", skill.getConversationCount() != null ? skill.getConversationCount() : 0);
stats.put("userCount", skill.getUserCount() != null ? skill.getUserCount() : 0);
stats.put("satisfactionRate", skill.getSatisfactionRate() != null ? skill.getSatisfactionRate() : 0);
```

然后在 builder 链中追加 `.stats(stats)`。不需要注入新 Repository。

---

## 改动 8：ShareInfoResponse DTO

**文件：** `backend/src/main/java/com/aiextract/dto/ShareInfoResponse.java`

在第 59 行 `openingMessage` 字段之后、类结束 `}` 之前追加：

```java
/** 聚合互动统计（来自 skill 表缓存字段，无数据时各值为 0） */
private Map<String, Object> stats;
```

---

## 改动 9：PublicController — listPublicSkills 追加 stats

**文件：** `backend/src/main/java/com/aiextract/controller/PublicController.java`

不需要注入新 Repository。在 `listPublicSkills()` 循环内的 `item.put` 部分（第 116 行附近），追加：

```java
// 互动统计 — 从 Skill 实体缓存字段直接读
Map<String, Object> stats = new LinkedHashMap<>();
stats.put("conversationCount", skill.getConversationCount() != null ? skill.getConversationCount() : 0);
stats.put("userCount", skill.getUserCount() != null ? skill.getUserCount() : 0);
stats.put("satisfactionRate", skill.getSatisfactionRate() != null ? skill.getSatisfactionRate() : 0);
item.put("stats", stats);
```

---

## 改动 10：TrustBadge — 升级为动态数据

**文件：** `packages/shared-ui/src/components/TrustBadge.tsx`

### 当前（第 5-10 行，纯静态）：
```tsx
const TRAITS = [
  { icon: '♡', title: '真实案例', desc: '销冠真实对话与文档提炼' },
  { icon: '✦', title: '溯源可查', desc: '每句回答有据可依' },
  { icon: '▤', title: '即学即用', desc: '30秒拿到可执行话术' },
];
```

### 改为（新增 props，动态/静态自动切换）：

```tsx
interface TrustBadgeProps {
  /** 活跃颗粒总数 — 有值时第一列显示 "♡ XX 条真实经验" */
  grainCount?: number;
  /** 场景覆盖数 — 有值时第二列显示 "✦ XX 个业务场景" */
  sceneCount?: number;
  /** 满意度百分比 0-100 — 有值时第二列追加满意度 */
  satisfactionRate?: number;
  /** 最近活跃描述 — 有值时第三列显示 "▤ {lastActive}" */
  lastActive?: string;
}

export function TrustBadge({ grainCount, sceneCount, satisfactionRate, lastActive }: TrustBadgeProps) {
```

三列数据的取值逻辑：
```tsx
// 第一列
const col1Title = grainCount != null && grainCount > 0 ? '真实经验' : '真实案例';
const col1Desc = grainCount != null && grainCount > 0
  ? `${grainCount} 条销冠实战经验` : '销冠真实对话与文档提炼';

// 第二列
const col2Title = sceneCount != null && sceneCount > 0 ? '场景覆盖' : '溯源可查';
const col2Desc = sceneCount != null && sceneCount > 0
  ? `${sceneCount} 个业务场景${satisfactionRate != null && satisfactionRate > 0 ? ` · 👍${satisfactionRate}% 满意` : ''}`
  : '每句回答有据可依';

// 第三列
const col3Title = lastActive ? '最近活跃' : '即学即用';
const col3Desc = lastActive || '30秒拿到可执行话术';
```

**向后兼容：** 不传任何 props → 行为与当前完全一致（三个静态文案）。

---

## 改动 11：StatBadge — 新建三端统一组件

**文件：** `packages/shared-ui/src/components/StatBadge.tsx`（新建）

```tsx
'use client';

import React from 'react';

export interface StatBadgeProps {
  value: number;
  label: string;
  icon?: string;
  size?: 'sm' | 'md';
  hideOnZero?: boolean;
}

const SIZE_MAP = {
  sm: { value: 13, label: 10, gap: 3 },
  md: { value: 15, label: 11, gap: 4 },
};

export function StatBadge({ value, label, icon, size = 'md', hideOnZero = true }: StatBadgeProps) {
  if (hideOnZero && value <= 0) return null;
  const s = SIZE_MAP[size];
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'baseline', gap: s.gap,
      fontSize: s.value, fontWeight: 600, color: '#1e293b',
    }}>
      {icon && <span style={{ fontSize: s.value - 1 }}>{icon}</span>}
      <span>{value.toLocaleString()}</span>
      <span style={{ fontSize: s.label, fontWeight: 400, color: '#64748b' }}>{label}</span>
    </span>
  );
}
```

---

## 改动 12：shared-ui 导出

**文件：** `packages/shared-ui/src/index.ts`

在 exports 中追加：
```ts
export { StatBadge } from './components/StatBadge';
export type { StatBadgeProps } from './components/StatBadge';
```

---

## 改动 13：frontend API 类型 — SkillInfo

**文件：** `frontend/src/lib/api/skill.ts`

### 当前（第 29-32 行）：
```ts
export interface SkillInfo {
  id: string; spaceId: string; ownerName: string; ownerTitle: string;
  status: string; modelName?: string; grainCount?: number;
}
```

### 改为：
```ts
export interface SkillStats {
  conversationCount: number;
  userCount: number;
  satisfactionRate: number;  // 0-100
  lastActive?: string;       // ISO timestamp
}

export interface SkillInfo {
  id: string; spaceId: string; ownerName: string; ownerTitle: string;
  status: string; modelName?: string; grainCount?: number;
  avatarUrl?: string;
  tags?: string[];
  openingMessage?: string;
  domain?: string;
  stats?: SkillStats;
}
```

---

## 改动 14：C 端 ShareInfo 类型

**文件：** `frontend/src/lib/api/c.ts`

在 `ShareInfo` 接口（第 30-42 行）中追加：
```ts
  /** 聚合互动统计（来自 skill 表缓存，可能全为 0） */
  stats?: {
    conversationCount: number;
    userCount: number;
    satisfactionRate: number;
  };
```

---

## 改动 15：Enterprise API 类型

**文件：** `enterprise/src/lib/api/skill.ts`

`SkillDetail`（第 40-53 行）追加：
```ts
  stats?: {
    conversationCount: number;
    userCount: number;
    satisfactionRate: number;
  };
  sceneCount?: number; // 场景覆盖数（从 sceneTags.length 推算）
```

`PublicSkillInfo`（第 19-30 行）追加：
```ts
  stats?: {
    conversationCount: number;
    userCount: number;
    satisfactionRate: number;
  };
```

---

## 改动 16：分身广场卡片 — `/skills` 页

**文件：** `frontend/src/app/skills/page.tsx`

### 当前卡片（第 64-86 行）：

```tsx
<button key={s.id} onClick={...}
  className="rounded-2xl bg-surface-2 p-6 ... hover:-translate-y-0.5">
  <div className="flex items-center gap-4">
    <div className="flex h-14 w-14 items-center justify-center rounded-full
      bg-gradient-to-br from-navy to-primary text-white text-xl font-bold">
      {(s.ownerName || '?')[0]}
    </div>
    <div className="flex-1 min-w-0">
      <h3 className="font-semibold text-foreground truncate">{s.ownerName}</h3>
      <p className="text-xs text-muted-foreground-2">{s.ownerTitle || '资深销冠'}</p>
      <div className="mt-1 flex items-center gap-2">
        <span className="...">● 在线</span>
        <span className="text-xs text-muted-foreground-2">{s.grainCount || 0} 条锦囊</span>
      </div>
    </div>
  </div>
</button>
```

### 改为：

```
┌─────────────────────────────────────┐
│                                     │
│  [PortraitCard 蛋形 80x65]          │  ← 替换首字母圆圈
│                                     │
│  张经理                             │
│  金融事业部销冠                      │
│                                     │
│  ┌───────────────────────────────┐  │
│  │ 💬 128 次  ·  👍 94%  ·  👤 89 │  │  ← stats 条（仅 stats 存在时显示）
│  └───────────────────────────────┘  │
│                                     │
│  [价格谈判] [异议处理] [大客户]       │  ← 最多3个场景pill（取 tags 或 sceneTags）
│                                     │
│  45 条经验 · 8 个场景               │  ← 颗粒+场景数
│                                     │
└─────────────────────────────────────┘
```

具体代码改动：

1. **Import**：加 `import { PortraitCard, StatBadge } from '@aiextract/shared-ui';`
2. **Avatar**：56px 首字母圆圈 → `<PortraitCard src={s.avatarUrl} alt={s.ownerName} />`（设 width: 80）
3. **删除**：第 77-79 行的 `● 在线` 假徽章 → 整个删除
4. **新增 stats 行**（在 title 下方插入）：
```tsx
{s.stats && s.stats.conversationCount > 0 && (
  <div className="mt-2 flex items-center gap-2 text-xs"
    style={{ background: '#f8faff', borderRadius: 8, padding: '4px 10px' }}>
    <StatBadge icon="💬" value={s.stats.conversationCount} label="次" size="sm" />
    {s.stats.satisfactionRate > 0 && (
      <><span className="text-[#d4d8e0]">·</span>
      <StatBadge icon="👍" value={s.stats.satisfactionRate} label="%" size="sm" /></>
    )}
    {s.stats.userCount > 0 && (
      <><span className="text-[#d4d8e0]">·</span>
      <StatBadge icon="👤" value={s.stats.userCount} label="人" size="sm" /></>
    )}
  </div>
)}
```
5. **新增场景 pill**（stats 行下方）：
```tsx
{s.tags && s.tags.length > 0 && (
  <div className="mt-2 flex flex-wrap gap-1">
    {s.tags.slice(0, 3).map(tag => (
      <span key={tag} className="rounded-full bg-[#eef2ff] px-2 py-0.5 text-[10px] text-[#475569]">
        {tag}
      </span>
    ))}
  </div>
)}
```
6. **底部 meta**：`"{s.grainCount || 0} 条锦囊"` → `"{s.grainCount || 0} 条经验"`。`sceneCount` 如果 API 返回了 `tags` 也用 `tags.length` 显示
7. **hover**：保持 `hover:-translate-y-0.5 hover:shadow-md`

---

## 改动 17：分身详情 Hero — `/skill/[id]/page.tsx`

**文件：** `frontend/src/app/skill/[skillId]/page.tsx`

### 17a. 加载 detail 时保存 stats（第 53-63 行）

当前 useEffect 只提取 `openingMessage` 和 `avatarUrl`。改为同时保存：

```tsx
const [skillStats, setSkillStats] = useState<{
  conversationCount: number; userCount: number; satisfactionRate: number;
} | null>(null);
const [grainCount, setGrainCount] = useState(0);
const [sceneCount, setSceneCount] = useState(0);
const [skillTags, setSkillTags] = useState<string[]>([]);

// fetch then 中追加：
.then(d => {
  // ... 现有逻辑 ...
  if (d?.data?.stats) setSkillStats(d.data.stats);
  if (d?.data?.grainCount) setGrainCount(d.data.grainCount);
  if (d?.data?.sceneTags) setSceneCount(d.data.sceneTags.length);
  if (d?.data?.tags) setSkillTags(d.data.tags);
})
```

### 17b. QA/Talk 名片卡片（第 202-235 行）

**替换价值主张文案**（第 221-227 行 — 当前是固定 `TALK_NAME_CARD.valueProp`）：
```tsx
{/* 当前：固定文案 */}
<p className="mt-3 text-[16px] text-foreground/85 leading-relaxed whitespace-pre-wrap">
  {TALK_NAME_CARD.valueProp.split(...).map(...)}
</p>

{/* 改为：数据驱动 */}
<p className="mt-3 text-[16px] text-foreground/85 leading-relaxed">
  已采集 {grainCount > 0 ? grainCount : '...'} 条实战经验
  {sceneCount > 0 && <>，覆盖 {sceneCount} 个业务场景</>}
</p>
```

**追加 stats 条**（在 ownerTitle 之后、valueProp 之前）：
```tsx
{skillStats && skillStats.conversationCount > 0 && (
  <div className="mt-2 flex items-center gap-4">
    <StatBadge icon="💬" value={skillStats.conversationCount} label="次对话" size="md" />
    {skillStats.satisfactionRate > 0 && (
      <><span className="text-[#d4d8e0]">·</span>
      <StatBadge icon="👍" value={skillStats.satisfactionRate} label="% 满意" size="md" /></>
    )}
    {skillStats.userCount > 0 && (
      <><span className="text-[#d4d8e0]">·</span>
      <StatBadge icon="👤" value={skillStats.userCount} label="人用过" size="md" /></>
    )}
  </div>
)}
```

**追加场景 pill**（valueProp 下方）：
```tsx
{skillTags.length > 0 && (
  <div className="mt-2 flex flex-wrap gap-1.5">
    {skillTags.slice(0, 4).map(tag => (
      <span key={tag} className="inline-block rounded-full bg-[#eef2ff] px-2.5 py-0.5 text-[11px] text-[#475569]">
        {tag}
      </span>
    ))}
  </div>
)}
```

**TrustBadge 改为动态**（第 233 行）：
```tsx
// 当前
<TrustBadge />

// 改为
<TrustBadge
  grainCount={grainCount > 0 ? grainCount : undefined}
  sceneCount={sceneCount > 0 ? sceneCount : undefined}
  satisfactionRate={skillStats?.satisfactionRate}
/>
```

### 17c. Practice 模式名片卡片（第 139-179 行）

做与 QA/Talk 相同的改动：
- 第 150-161 行：价值主张文案 → 数据驱动（同上）
- 第 163 行：`<TrustBadge />` → 动态 props

---

## 改动 18：H5 ShareLanding

**文件：** `frontend/src/app/s/[shareCode]/ShareLanding.tsx`

### 18a. Hero 区 stats（第 76-79 行之后）

在 name + title 下方、渐变 Hero 闭合前：

```tsx
{/* 数据证明 — 渐变 Hero 内嵌 */}
{info.stats && info.stats.conversationCount > 0 && (
  <div className="mt-3 flex items-center justify-center gap-3 text-white/80 text-[12px]">
    <span>💬 {info.stats.conversationCount.toLocaleString()} 次对话</span>
    {info.stats.satisfactionRate > 0 && (
      <><span className="text-white/30">·</span><span>👍 {info.stats.satisfactionRate}% 满意</span></>
    )}
  </div>
)}
```

### 18b. 上叠白卡文案（第 84-86 行）

```tsx
{/* 当前 */}
把{name}的实战打法，浓缩成你随时可问的 AI 分身

{/* 改为 */}
{info.stats?.userCount ? (
  <>已帮助 {info.stats.userCount} 位销售同行解决实际问题</>
) : (
  <>把{name}的实战打法，浓缩成你随时可问的 AI 分身</>
)}
```

Tag chips 下方加 meta：
```tsx
<div className="mt-2 text-[11px] text-muted-foreground">
  {(info.sceneTags?.length || 0) > 0 && <>{info.sceneTags!.length} 个场景</>}
</div>
```

### 18c. 信任条数据化（第 96-134 行）

三列文案改为动态优先、静态回退：
- 第一列：有 `stats.conversationCount` → `"{count} 次"` / `"真实对话交流"`，无 → `"实战打法"` / `"销冠真实案例提炼"`
- 第二列：有 `satisfactionRate` → `"{rate}% 满意"` / `"回答被认可"`，无 → `"溯源可查"` / `"每句话有据可依"`
- 第三列：有 `sceneTags.length` → `"{n} 个场景"` / `"经验全面覆盖"`，无 → `"即问即用"` / `"30秒拿到可执行话术"`

### 18d. 三模式卡片底部统计（第 136-161 行）

每个模式卡片的 `<span className="min-w-0 flex-1">` 最下方追加：
```tsx
{info.stats && card.mode !== 'practice' && info.stats.conversationCount > 0 && (
  <span className="mt-0.5 block text-[10px] text-muted-foreground">
    {info.stats.conversationCount} 次对话
  </span>
)}
{card.mode === 'practice' && (info.sceneTags?.length || 0) > 0 && (
  <span className="mt-0.5 block text-[10px] text-muted-foreground">
    {info.sceneTags!.length} 个场景
  </span>
)}
```

---

## 改动 19：MobileChatShell Hero

**文件：** `frontend/src/app/s/[shareCode]/MobileChatShell.tsx`

与改动 17（B 端 Hero）保持一致的数据驱动模式：

1. **名片卡片**（第 223-251 行 QA/Talk，第 138-164 行 Practice）：stats 条用 `StatBadge`（size='sm' 适配移动端），valueProp 改为数据事实，TrustBadge 改动态
2. **场景卡片**（第 287-310 行 QA）：第一个场景追加 `i === 0 && <推荐徽章>`

---

## 改动 20：Enterprise ChatEntry Hero

**文件：** `enterprise/src/components/chat/ChatEntry.tsx`

在名片卡片（第 66-109 行）中，第 105 行 `skill.department` 之后追加 stats 条：

```tsx
{/* Stats 条 — 企业端主题色 */}
{skill.stats && skill.stats.conversationCount > 0 && (
  <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginTop: 10 }}>
    <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--fg-high)' }}>
      💬 {skill.stats.conversationCount.toLocaleString()} 次对话
    </span>
    {skill.stats.satisfactionRate > 0 && (
      <><span style={{ color: 'var(--fg-dim)' }}>·</span>
      <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--fg-high)' }}>
        👍 {skill.stats.satisfactionRate}% 满意
      </span></>
    )}
  </div>
)}
```

第 91-99 行 valueProp 改为数据驱动。第 108 行 TrustBadge 改动态 props。

---

## 改动 21：Enterprise ExpertRow

**文件：** `enterprise/src/components/discover/ExpertRow.tsx`

第 133-135 行的底部 meta 行：
```tsx
// 当前
<div style={{ fontSize: 10, color: 'var(--fg-dim)', marginTop: 4 }}>
  {skill.grainCount} 条经验 · {skill.domain || tags.join('/')}
</div>

// 改为
{skill.stats && skill.stats.conversationCount > 0 ? (
  <div style={{ fontSize: 10, color: 'var(--fg-dim)', marginTop: 4 }}>
    💬 {skill.stats.conversationCount} 次对话
    {skill.stats.satisfactionRate > 0 && <> · 👍 {skill.stats.satisfactionRate}%</>}
  </div>
) : (
  <div style={{ fontSize: 10, color: 'var(--fg-dim)', marginTop: 4 }}>
    {skill.grainCount} 条经验 · {skill.domain || tags.join('/')}
  </div>
)}
```

---

## 降级策略（所有端统一）

| 条件 | 展示 |
|------|------|
| `stats` 为 undefined/null | 整个 stats 行不渲染，保持现有纯静态布局 |
| `conversationCount === 0` | StatBadge 的 `hideOnZero` 默认 true → 不渲染 |
| `satisfactionRate === 0` | 同上 → 不渲染该列（0% 和 "没数据" 在 UI 上分不清，不展示更安全） |
| `userCount === 0` | 同上 → 不渲染 |
| `grainCount` 未传 | TrustBadge 回退到静态文案 "真实案例" |
| `sceneCount` 未传 | TrustBadge 回退到静态文案 "溯源可查" |
| `lastActive` 未传 | TrustBadge 第三列回退到 "即学即用" |
| 新分身（Scheduler 还没跑第一次） | 所有字段为 0 → 外观与改动前完全一致 |

**核心原则：有数据就展示、没数据就隐藏、绝不展示 "0 次对话"。**

---

## 验证清单

1. **编译** — `mvn compile` + `npm run build`（frontend + enterprise）全部通过
2. **Migration** — V30 执行后 skill 表多 4 列，已有行字段全为 0 非 NULL
3. **Scheduler 首跑** — 启动后 5 分钟内有对话记录的分身 `conversation_count > 0`
4. **is_test 过滤** — 管理员在审核页测试 QA/Practice 后，分身 stats 不变（排除测试数据）
5. **防重入** — 连续两个 cron 触发，第二次 `log.debug("跳过本次执行")`
6. **API 返回** — `GET /skills/{id}/detail` / `GET /skills/list` / `GET /public/share/{code}` 全部包含 stats 对象
7. **新分身降级** — stats 全 0 时三端页面与改动前完全一致
8. **有数据展示** — 在有对话记录的分身上验证：B 端卡片/Hero、H5 ShareLanding/MobileChatShell、企业端 ExpertRow/ChatEntry
9. **TrustBadge 向后兼容** — 现有 `<TrustBadge />` 调用（无 props）行为不变
10. **移动端** — H5 在 375px 宽度下无溢出、无折行
