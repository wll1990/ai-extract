package com.aiextract.service;

import com.aiextract.common.AiErrorMapper;
import com.aiextract.config.PromptLoader;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.EvaluationDataset;
import com.aiextract.model.EvaluationGenerationJob;
import com.aiextract.model.EvaluationItem;
import com.aiextract.model.IndustryCase;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.repository.EvaluationDatasetRepository;
import com.aiextract.repository.EvaluationGenerationJobRepository;
import com.aiextract.repository.EvaluationItemRepository;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AI 生成候选任务服务 —— 从知识/案例异步生成候选评测题目。
 *
 * <p>流程：Controller 校验来源可读后调 {@link #createJob}（同步建 running 任务）→
 * {@link #runGeneration}（异步逐素材出题）→ {@link #persistItems}（同一事务写入题目 + 标记 done）。
 * 任何异常走 {@link #markFailed}，AI 调用失败不造假、不降级。</p>
 *
 * <p>companyId 由题目所属题集决定（item.companyId = dataset.companyId），
 * 不取当前登录用户，保证 super_admin 也能给企业题集出题。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationGenerationService {

    private static final int ERROR_MESSAGE_MAX_LENGTH = 1000;

    /** 生成任务「卡死」判定阈值：超过该时长仍为 running 视为中断，允许下次创建时自动标记失败 */
    private static final long GENERATION_STALE_MINUTES = 30;

    /** 喂给模型的「已有题目」避让清单上限（题集变大后防止 prompt 无限膨胀） */
    private static final int PROMPT_EXCLUDE_MAX = 40;

    /** 避让清单单题截断长度（题干通常很短，超长的只取前段足够模型判重） */
    private static final int PROMPT_QUESTION_MAX_CHARS = 120;

    /** 事实引用片段的最短归一化长度：过短（如"话术"）无法定位唯一句子，一律作废 */
    private static final int FACT_QUOTE_MIN_MATCH = 6;

    /** fact_quote 落库截断长度，与 DDL varchar(200) 对齐 */
    private static final int FACT_QUOTE_MAX_CHARS = 200;

    /** 句子边界：中文/英文句末与换行（素材来自多格式解析，两种都要） */
    private static final char[] SENTENCE_DELIMS = {'。', '！', '？', '；', '!', '?', ';', '\n'};

    private final EvaluationDatasetRepository datasetRepository;
    private final EvaluationGenerationJobRepository jobRepository;
    private final EvaluationItemRepository itemRepository;
    private final KnowledgeEntryRepository knowledgeRepository;
    private final IndustryCaseRepository caseRepository;
    private final ChatClient chatClient;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;

    /** 自注入代理 —— @Async 与 @Transactional 方法内部互调需走代理，绕过 this 调用失效问题 */
    @Autowired
    @Lazy
    private EvaluationGenerationService self;

    // ==================== 同步创建任务 ====================

    /** 创建生成任务（status=running），返回任务 ID。来源合法性已由 Controller 校验。 */
    @Transactional(rollbackFor = Exception.class)
    public UUID createJob(UUID datasetId, String sourceType, List<UUID> sourceIds) {
        EvaluationDataset ds = datasetRepository.findById(datasetId)
                .orElseThrow(() -> new BusinessException(404, "题集不存在: " + datasetId));
        if (!EvaluationDataset.STATUS_DRAFT.equals(ds.getStatus())) {
            throw new BusinessException(400, "仅草稿状态的题集可生成候选");
        }
        if (!EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE.equals(sourceType)
                && !EvaluationGenerationJob.SOURCE_TYPE_CASE.equals(sourceType)) {
            throw new BusinessException(400, "非法来源类型: " + sourceType);
        }
        recoverStaleRunningJob(datasetId);
        EvaluationGenerationJob job = EvaluationGenerationJob.builder()
                .id(UUID.randomUUID())
                .datasetId(datasetId)
                .sourceType(sourceType)
                .status(EvaluationGenerationJob.STATUS_RUNNING)
                .producedCount(0)
                .createdBy(TokenContext.get())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        try {
            // saveAndFlush 立即执行 INSERT，触发 partial unique index（dataset_id WHERE status='running'），
            // 并发连续点击时第二个 INSERT 违反唯一约束 → 409，DB 级原子兜底。
            jobRepository.saveAndFlush(job);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(409, "该题集已有生成任务进行中，请等待完成");
        }
        log.info("创建候选生成任务 jobId={}, datasetId={}, sourceType={}, sourceCount={}",
                job.getId(), datasetId, sourceType, sourceIds.size());
        return job.getId();
    }

    // ==================== 异步生成 ====================

    /** 异步生成候选：逐素材调用 AI 出题，全部成功写库后标记 done，异常标记 failed。 */
    @Async("generationExecutor")
    public void runGeneration(UUID jobId, String sourceType, List<UUID> sourceIds) {
        try {
            EvaluationGenerationJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null) {
                log.warn("候选生成任务不存在，跳过 jobId={}", jobId);
                return;
            }
            EvaluationDataset ds = datasetRepository.findById(job.getDatasetId()).orElse(null);
            if (ds == null) {
                self.markFailed(jobId, "题集不存在");
                return;
            }

            // 题序从当前版本已有题目的 max+1 递增，避免与手动/历史生成的题目撞序
            int nextOrderNo = itemRepository.findMaxOrderNo(ds.getId(), ds.getCurrentVersion());
            // 题干基线：挡「一字不差」的重复题（历史手工题没有 fact_key，只能靠这一层）
            List<String> existingQuestions = new ArrayList<>(itemRepository
                    .findQuestionsByDatasetIdAndVersionAndStatus(
                            ds.getId(), ds.getCurrentVersion(), EvaluationItem.STATUS_ACTIVE));
            Set<String> seenQuestions = new HashSet<>();
            existingQuestions.forEach(q -> seenQuestions.add(normalizeQuestion(q)));
            // 事实基线：题干措辞会变，事实不会。标识落在素材句子上，重复出题可稳定判重
            Set<String> seenFactKeys = new HashSet<>(itemRepository
                    .findFactKeysByDatasetIdAndVersionAndStatus(
                            ds.getId(), ds.getCurrentVersion(), EvaluationItem.STATUS_ACTIVE));
            List<String> coveredFacts = new ArrayList<>(itemRepository
                    .findFactQuotesByDatasetIdAndVersionAndStatus(
                            ds.getId(), ds.getCurrentVersion(), EvaluationItem.STATUS_ACTIVE));

            List<EvaluationItem> items = new ArrayList<>();
            int skippedExact = 0;
            int skippedFact = 0;
            int rejectedNoFact = 0;
            for (UUID sourceId : sourceIds) {
                SourceText source = loadSource(sourceType, sourceId);
                if (source == null) {
                    log.warn("来源条目不存在，跳过 sourceType={}, sourceId={}", sourceType, sourceId);
                    continue;
                }
                List<FactCandidate> generated = generateFromSource(
                        ds, sourceType, source, existingQuestions, coveredFacts);
                for (FactCandidate candidate : generated) {
                    EvaluationItem item = candidate.item();
                    String sentence = resolveFactSentence(source.content(), candidate.factQuote());
                    if (sentence == null) {
                        // 没有可定位的事实引用 → 该题没有去重依据，宁可丢弃也不能让题集里出现无法判重的题
                        rejectedNoFact++;
                        log.warn("候选题事实引用无法定位，已丢弃 datasetId={}, sourceId={}, factQuote={}, question={}",
                                ds.getId(), sourceId, candidate.factQuote(), item.getQuestion());
                        continue;
                    }
                    String factKey = factKeyOf(sentence);
                    if (!seenQuestions.add(normalizeQuestion(item.getQuestion()))) {
                        skippedExact++;
                        continue;
                    }
                    if (!seenFactKeys.add(factKey)) {
                        skippedFact++;
                        continue;
                    }
                    item.setFactKey(factKey);
                    item.setFactQuote(truncateQuote(sentence));
                    item.setOrderNo(++nextOrderNo);
                    // 标记 AI 生成，便于出题人与人工题区分
                    item.setGenerated(true);
                    items.add(item);
                    // 同批次后续素材也要避让本批已接受题目的题干与事实
                    existingQuestions.add(item.getQuestion());
                    coveredFacts.add(item.getFactQuote());
                }
            }
            if (skippedExact > 0 || skippedFact > 0 || rejectedNoFact > 0) {
                log.info("候选生成去重 jobId={}, skippedExact={}, skippedFact={}, rejectedNoFact={}, kept={}",
                        jobId, skippedExact, skippedFact, rejectedNoFact, items.size());
            }
            self.persistItems(jobId, items, skippedExact + skippedFact, rejectedNoFact);
        } catch (Exception e) {
            log.error("候选生成失败 jobId={}", jobId, e);
            String message = AiErrorMapper.describe(e);
            self.markFailed(jobId, truncate(message));
        }
    }

    /** 事务内写入候选题目并标记任务完成（题目 + 任务状态同事务，避免部分成功）。 */
    @Transactional(rollbackFor = Exception.class)
    public void persistItems(UUID jobId, List<EvaluationItem> items, int skipped, int rejected) {
        if (!items.isEmpty()) {
            itemRepository.saveAll(items);
        }
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(EvaluationGenerationJob.STATUS_DONE);
            job.setProducedCount(items.size());
            job.setSkippedCount(skipped);
            job.setRejectedCount(rejected);
            jobRepository.save(job);
        });
        log.info("候选生成完成 jobId={}, producedCount={}", jobId, items.size());
    }

    /** 事务内标记任务失败（尽力而为，失败日志已记录）。 */
    @Transactional(rollbackFor = Exception.class)
    public void markFailed(UUID jobId, String message) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(EvaluationGenerationJob.STATUS_FAILED);
            job.setErrorMessage(message);
            jobRepository.save(job);
        });
    }

    // ==================== 私有辅助 ====================

    /** 卡死恢复：running 且超时的任务惰性标记失败（saveAndFlush 先落库，保证随后 INSERT 不被唯一索引误拦）。 */
    private void recoverStaleRunningJob(UUID datasetId) {
        jobRepository.findFirstByDatasetIdAndStatusOrderByCreatedAtDesc(
                        datasetId, EvaluationGenerationJob.STATUS_RUNNING)
                .ifPresent(running -> {
                    if (isStale(running)) {
                        running.setStatus(EvaluationGenerationJob.STATUS_FAILED);
                        running.setErrorMessage("生成任务超时中断，已自动标记失败");
                        jobRepository.saveAndFlush(running);
                        log.warn("标记卡死的生成任务失败 jobId={}, datasetId={}", running.getId(), datasetId);
                    }
                });
    }

    /** 任务超过阈值仍为 running 视为中断（无心跳，updated_at 只在创建/结束时更新）。 */
    private boolean isStale(EvaluationGenerationJob job) {
        if (job.getUpdatedAt() == null) {
            return false;
        }
        return job.getUpdatedAt().isBefore(LocalDateTime.now().minusMinutes(GENERATION_STALE_MINUTES));
    }

    /** 读取来源内容，返回 null 表示条目不存在（已被删除）。 */
    private SourceText loadSource(String sourceType, UUID sourceId) {
        if (EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE.equals(sourceType)) {
            KnowledgeEntry k = knowledgeRepository.findById(sourceId).orElse(null);
            if (k == null) {
                return null;
            }
            String content = "标题：" + nvl(k.getTitle())
                    + "\n分类：" + nvl(k.getCategory())
                    + "\n正文：\n" + nvl(k.getContent());
            return new SourceText(k.getTitle(), content);
        }
        IndustryCase c = caseRepository.findById(sourceId).orElse(null);
        if (c == null) {
            return null;
        }
        String content = "标题：" + nvl(c.getTitle())
                + "\n漏斗阶段：" + nvl(c.getFunnelStage())
                + "\n场景标签：" + nvl(c.getSceneTag())
                + "\n核心异议：" + nvl(c.getObjection())
                + "\n关键动作：" + nvl(c.getKeyActions())
                + "\n话术：" + nvl(c.getScripts())
                + "\n可复用点：" + nvl(c.getReusablePoints())
                + "\n失败点：" + nvl(c.getFailurePoints());
        return new SourceText(c.getTitle(), content);
    }

    /**
     * 对单个素材调用 AI 出题，解析为「题目 + 事实引用」候选；JSON 解析失败返回空（不算致命失败）。
     *
     * <p>模型只负责逐字摘抄事实片段（factQuote），标识由服务端吸附到素材句子后计算，
     * 避免让模型自报编号导致两轮生成标识不一致。</p>
     */
    private List<FactCandidate> generateFromSource(EvaluationDataset ds, String sourceType,
                                                   SourceText source, List<String> existingQuestions,
                                                   List<String> coveredFacts) {
        String prompt = promptLoader.format("eval_generate_item.md", Map.of(
                "source_type", EvaluationGenerationJob.SOURCE_TYPE_CASE.equals(sourceType) ? "案例" : "知识",
                "source_content", source.content(),
                "purpose", nvl(ds.getPurpose()),
                "exclude_questions", buildExcludeBlock(existingQuestions),
                "exclude_facts", buildFactBlock(coveredFacts)
        ));
        String raw = chatClient.prompt().user(prompt).call().content();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String cleaned = raw.trim()
                .replaceAll("^```(?:json)?\\s*", "")
                .replaceAll("\\s*```$", "");

        List<Map<String, Object>> rawItems;
        try {
            rawItems = objectMapper.readValue(cleaned, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("AI 出题 JSON 解析失败，跳过该素材: {}", e.getMessage());
            return List.of();
        }

        LocalDateTime now = LocalDateTime.now();
        List<FactCandidate> result = new ArrayList<>();
        for (Map<String, Object> m : rawItems) {
            String question = asText(m.get("question"));
            if (question == null) {
                continue;
            }
            String goldBasis = asText(m.get("goldBasis"));
            if (goldBasis == null) {
                goldBasis = source.title();
            }
            EvaluationItem item = EvaluationItem.builder()
                    .id(UUID.randomUUID())
                    .companyId(ds.getCompanyId())
                    .datasetId(ds.getId())
                    .version(ds.getCurrentVersion())
                    .question(question)
                    .context(asText(m.get("context")))
                    .expectedPoints(toJson(m.get("expectedPoints")))
                    .forbiddenPoints(toJson(m.get("forbiddenPoints")))
                    .citationRequirement(toJson(m.get("citationRequirement")))
                    .sceneTag(asText(m.get("sceneTag")))
                    .goldBasis(goldBasis)
                    .scoringRule(null)
                    .retrievalExpected(toJson(m.get("retrievalExpected")))
                    .orderNo(0)
                    // AI 只出草稿：人工审核通过后才 active，避免未经金标确认就进考试
                    .status(EvaluationItem.STATUS_DRAFT)
                    .generated(true)
                    .createdBy(TokenContext.get())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            // factQuote 缺失时也返回候选，由上层统一计入 rejectedNoFact（不在这里静默丢弃）
            result.add(new FactCandidate(item, asText(m.get("factQuote"))));
        }
        return result;
    }

    /**
     * 题干归一化：去掉所有空白、统一小写，用于同题判定。
     *
     * <p>只做「归一化后完全相同」的判定，不做语义相似：语义去重要调向量化，
     * 成本和延迟都不适合在生成任务的同步路径里做，近似重复留给人工审核挑。</p>
     */
    private String normalizeQuestion(String question) {
        if (question == null) {
            return "";
        }
        return question.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 拼「已有题目」避让清单，喂给模型在生成阶段就避开重复。
     *
     * <p>精确比对只能挡住一模一样的题干，同一份素材重跑会产出换个说法的复述，靠字符串挡不住，
     * 所以在 prompt 里把已有题干列出来让模型主动避让。清单上限 {@value #PROMPT_EXCLUDE_MAX} 条、
     * 单题截断 {@value #PROMPT_QUESTION_MAX_CHARS} 字，避免题集变大后 prompt 无限增长。</p>
     */
    private String buildExcludeBlock(List<String> existingQuestions) {
        if (existingQuestions.isEmpty()) {
            return "（本题集暂无已有题目）";
        }
        int limit = Math.min(existingQuestions.size(), PROMPT_EXCLUDE_MAX);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            String q = existingQuestions.get(i);
            if (q != null && q.length() > PROMPT_QUESTION_MAX_CHARS) {
                q = q.substring(0, PROMPT_QUESTION_MAX_CHARS) + "…";
            }
            sb.append(i + 1).append(". ").append(q).append('\n');
        }
        if (existingQuestions.size() > limit) {
            sb.append("（其余 ").append(existingQuestions.size() - limit).append(" 条略）\n");
        }
        return sb.toString();
    }

    /**
     * 拼「已覆盖事实」避让清单：把已出过题的事实原文喂给模型，避免它换种说法再出一次。
     *
     * <p>与题干清单同理，受 {@value #PROMPT_EXCLUDE_MAX} 条与单条截断限制，防止 prompt 膨胀。</p>
     */
    private String buildFactBlock(List<String> coveredFacts) {
        if (coveredFacts.isEmpty()) {
            return "（本题集暂无已覆盖事实）";
        }
        int limit = Math.min(coveredFacts.size(), PROMPT_EXCLUDE_MAX);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            String f = coveredFacts.get(i);
            if (f != null && f.length() > PROMPT_QUESTION_MAX_CHARS) {
                f = f.substring(0, PROMPT_QUESTION_MAX_CHARS) + "…";
            }
            sb.append(i + 1).append(". ").append(f).append('\n');
        }
        if (coveredFacts.size() > limit) {
            sb.append("（其余 ").append(coveredFacts.size() - limit).append(" 条略）\n");
        }
        return sb.toString();
    }

    // ==================== 事实标识 ====================

    /**
     * 把模型引用的事实片段吸附到素材句子；找不到返回 null（该候选作废）。
     *
     * <p>吸附而不是直接哈希引用：模型两轮可能摘抄同一句里的不同片段，
     * 落回句子粒度后标识才稳定。</p>
     */
    private String resolveFactSentence(String sourceContent, String quote) {
        String normalizedQuote = normalizeForMatch(quote);
        if (normalizedQuote.length() < FACT_QUOTE_MIN_MATCH) {
            return null;
        }
        for (String sentence : splitSentences(sourceContent)) {
            if (normalizeForMatch(sentence).contains(normalizedQuote)) {
                return sentence;
            }
        }
        return null;
    }

    /** 按中英文句末符与换行切分素材；归一化后过短的碎片（字段头等）直接丢弃。 */
    private List<String> splitSentences(String content) {
        List<String> sentences = new ArrayList<>();
        if (content == null) {
            return sentences;
        }
        int start = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            boolean delim = false;
            for (char d : SENTENCE_DELIMS) {
                if (c == d) {
                    delim = true;
                    break;
                }
            }
            if (!delim) {
                continue;
            }
            addSentence(sentences, content.substring(start, i));
            start = i + 1;
        }
        addSentence(sentences, content.substring(start));
        return sentences;
    }

    private void addSentence(List<String> sentences, String candidate) {
        if (normalizeForMatch(candidate).length() >= FACT_QUOTE_MIN_MATCH) {
            sentences.add(candidate.trim());
        }
    }

    /**
     * 归一化：只保留字母/数字/汉字并转小写（{@code Character.isLetterOrDigit} 对汉字返回 true）。
     *
     * <p>同时抹掉空白与全部中英文标点，因此排版差异不影响判等，也不必维护标点表。</p>
     */
    private String normalizeForMatch(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** factKey = SHA-256(归一化句子) 的 64 位小写十六进制。 */
    private String factKeyOf(String sentence) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalizeForMatch(sentence).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必带 SHA-256；走到这里说明运行环境被裁剪，必须暴露而不是静默退化
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** fact_quote 落库截断：保留到 200 字以内（含省略号），与 DDL varchar(200) 对齐。 */
    private String truncateQuote(String sentence) {
        if (sentence == null) {
            return null;
        }
        return sentence.length() <= FACT_QUOTE_MAX_CHARS
                ? sentence
                : sentence.substring(0, FACT_QUOTE_MAX_CHARS - 1) + "…";
    }

    private String asText(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o);
        return s.isBlank() ? null : s;
    }

    private String toJson(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof String s) {
            return s.isBlank() ? null : s;
        }
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("候选字段 JSON 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > ERROR_MESSAGE_MAX_LENGTH ? s.substring(0, ERROR_MESSAGE_MAX_LENGTH) : s;
    }

    private record SourceText(String title, String content) {}

    /** 模型原始候选：题干实体 + 其声明的事实引用（尚未吸附到句子、未落库）。 */
    private record FactCandidate(EvaluationItem item, String factQuote) {}
}
