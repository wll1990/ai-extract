package com.aiextract.service;

import com.aiextract.exception.BusinessException;
import com.aiextract.model.EvaluationDataset;
import com.aiextract.model.EvaluationItem;
import com.aiextract.model.EvaluationRun;
import com.aiextract.model.EvaluationRunItem;
import com.aiextract.repository.EvaluationDatasetRepository;
import com.aiextract.repository.EvaluationItemRepository;
import com.aiextract.repository.EvaluationRunItemRepository;
import com.aiextract.repository.EvaluationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 评测题目分析 —— 评分身回答质量的核心读模型。
 *
 * <p>难度 = 1 - 通过率；区分度 = 高分组通过率 - 低分组通过率；
 * 争议率 = 人工复核推翻 / 复核总数。全部从已有 run_item/review 现算，不落冗余表。</p>
 */
@Service
@RequiredArgsConstructor
public class EvaluationAnalysisService {

    private final EvaluationDatasetRepository datasetRepository;
    private final EvaluationItemRepository itemRepository;
    private final EvaluationRunRepository runRepository;
    private final EvaluationRunItemRepository runItemRepository;
    private final TrainingAccessService accessService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public record ItemAnalysis(UUID itemId, String question, String sceneTag, int attempts,
                               int passed, double passRate, double difficulty,
                               double discrimination, long reviews, long overturns,
                               double overturnRate, List<String> flags) {
    }

    public record DatasetAnalysis(UUID datasetId, int itemCount, int analyzedItems,
                                  int neverRun, int tooEasy, int tooHard,
                                  int lowDiscrimination, int disputed,
                                  List<ItemAnalysis> items) {
    }

    @Transactional(readOnly = true)
    public DatasetAnalysis datasetAnalysis(UUID datasetId) {
        EvaluationDataset dataset = requireDataset(datasetId);
        UUID companyId = requireCompany();
        int version = dataset.getCurrentVersion() == null ? 1 : dataset.getCurrentVersion();
        List<EvaluationItem> items = itemRepository
                .findByDatasetIdAndVersionAndStatus(datasetId, version, EvaluationItem.STATUS_ACTIVE);

        Map<UUID, long[]> base = new HashMap<>();
        for (Object[] row : runItemRepository.aggregateItemStats(datasetId, companyId)) {
            base.put((UUID) row[0], new long[]{
                    ((Number) row[1]).longValue(),          // attempts
                    ((Number) row[2]).longValue(),          // passed
                    row[3] == null ? 0 : Math.round(((Number) row[3]).doubleValue())
            });
        }

        Map<UUID, Map<UUID, Boolean>> passByRun = new HashMap<>();
        Map<UUID, Double> scoreByRun = new HashMap<>();
        Map<UUID, Double> sumByRun = new HashMap<>();
        Map<UUID, Integer> countByRun = new HashMap<>();
        for (Object[] row : runItemRepository.findItemRunOutcomes(datasetId, companyId)) {
            UUID itemId = (UUID) row[0];
            UUID runId = (UUID) row[1];
            boolean pass = "pass".equals(String.valueOf(row[2]));
            double score = row[3] == null ? 0 : ((Number) row[3]).doubleValue();
            passByRun.computeIfAbsent(itemId, k -> new HashMap<>()).put(runId, pass);
            sumByRun.merge(runId, score, Double::sum);
            countByRun.merge(runId, 1, Integer::sum);
        }
        sumByRun.forEach((runId, sum) -> scoreByRun.put(runId, sum / Math.max(1, countByRun.get(runId))));
        double median = median(scoreByRun.values().stream().toList());

        Map<UUID, long[]> reviews = new HashMap<>();
        for (Object[] row : runItemRepository.aggregateReviewStats(datasetId, companyId)) {
            reviews.put((UUID) row[0], new long[]{
                    ((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
        }

        List<ItemAnalysis> analyses = new ArrayList<>();
        int neverRun = 0;
        int tooEasy = 0;
        int tooHard = 0;
        int lowDisc = 0;
        int disputed = 0;
        for (EvaluationItem item : items) {
            long[] stat = base.getOrDefault(item.getId(), new long[]{0, 0, 0});
            int attempts = (int) stat[0];
            int passed = (int) stat[1];
            double passRate = attempts == 0 ? 0 : (double) passed / attempts;
            double difficulty = attempts == 0 ? 0 : 1 - passRate;
            double discrimination = attempts == 0 ? 0
                    : discrimination(passByRun.getOrDefault(item.getId(), Map.of()), scoreByRun, median);
            long[] rev = reviews.getOrDefault(item.getId(), new long[]{0, 0});
            double overturnRate = rev[0] == 0 ? 0 : (double) rev[1] / rev[0];

            List<String> flags = new ArrayList<>();
            if (attempts == 0) {
                flags.add("never_run");
                neverRun++;
            } else {
                if (attempts >= 3 && passRate >= 0.9) {
                    flags.add("too_easy");
                    tooEasy++;
                }
                if (attempts >= 3 && passRate <= 0.2) {
                    flags.add("too_hard");
                    tooHard++;
                }
                if (attempts >= 4 && Math.abs(discrimination) < 0.2) {
                    flags.add("low_discrimination");
                    lowDisc++;
                }
            }
            if (rev[0] >= 2 && overturnRate >= 0.2) {
                flags.add("disputed");
                disputed++;
            }
            analyses.add(new ItemAnalysis(item.getId(), item.getQuestion(), item.getSceneTag(),
                    attempts, passed, round3(passRate), round3(difficulty),
                    round3(discrimination), rev[0], rev[1], round3(overturnRate), flags));
        }
        analyses.sort(Comparator.comparingDouble(ItemAnalysis::discrimination));
        return new DatasetAnalysis(datasetId, items.size(), analyses.size(), neverRun,
                tooEasy, tooHard, lowDisc, disputed, analyses);
    }

    /** 题目对比：两次 run 的逐题分差与结论翻转 */
    public record ItemDiff(UUID itemId, String question, Double baselineScore, Double currentScore,
                           String baselineVerdict, String currentVerdict, String change) {
    }

    public record RunDiff(UUID baselineRunId, UUID currentRunId, double baselineAvg,
                          double currentAvg, int improved, int regressed, int flippedPass,
                          int flippedFail, List<ItemDiff> items) {
    }

    @Transactional(readOnly = true)
    public RunDiff diffRuns(UUID datasetId, UUID baselineRunId, UUID currentRunId) {
        EvaluationRun baseline = requireRun(baselineRunId);
        EvaluationRun current = requireRun(currentRunId);
        if (!baseline.getDatasetId().equals(datasetId) || !current.getDatasetId().equals(datasetId)) {
            throw new BusinessException(400, "两次评测必须属于同一题集");
        }
        Map<UUID, EvaluationRunItem> left = latestByItem(baselineRunId);
        Map<UUID, EvaluationRunItem> right = latestByItem(currentRunId);
        Map<UUID, EvaluationItem> itemInfo = new HashMap<>();
        itemRepository.findByDatasetIdAndVersionAndStatus(datasetId,
                        current.getDatasetVersion() == null ? 1 : current.getDatasetVersion(),
                        EvaluationItem.STATUS_ACTIVE)
                .forEach(i -> itemInfo.put(i.getId(), i));

        int improved = 0;
        int regressed = 0;
        int flippedPass = 0;
        int flippedFail = 0;
        List<ItemDiff> diffs = new ArrayList<>();
        for (UUID itemId : right.keySet()) {
            EvaluationRunItem b = left.get(itemId);
            EvaluationRunItem c = right.get(itemId);
            Double bs = scoreOf(b);
            Double cs = scoreOf(c);
            String bv = b == null ? null : b.getVerdict();
            String cv = c == null ? null : c.getVerdict();
            String change = "same";
            if (bs != null && cs != null) {
                if (cs > bs + 2) { change = "improved"; improved++; }
                else if (cs < bs - 2) { change = "regressed"; regressed++; }
            }
            if ("fail".equals(bv) && "pass".equals(cv)) { change = "flipped_pass"; flippedPass++; }
            if ("pass".equals(bv) && "fail".equals(cv)) { change = "flipped_fail"; flippedFail++; }
            EvaluationItem item = itemInfo.get(itemId);
            diffs.add(new ItemDiff(itemId, item == null ? null : item.getQuestion(),
                    bs, cs, bv, cv, change));
        }
        diffs.sort(Comparator.comparing(ItemDiff::change));
        return new RunDiff(baselineRunId, currentRunId,
                round3(baseline.getScoreAvg() == null ? 0 : baseline.getScoreAvg().doubleValue()),
                round3(current.getScoreAvg() == null ? 0 : current.getScoreAvg().doubleValue()),
                improved, regressed, flippedPass, flippedFail, diffs);
    }

    private double discrimination(Map<UUID, Boolean> passByRun, Map<UUID, Double> scoreByRun,
                                  double median) {
        int topPass = 0, topTotal = 0, bottomPass = 0, bottomTotal = 0;
        for (Map.Entry<UUID, Boolean> e : passByRun.entrySet()) {
            double runAvg = scoreByRun.getOrDefault(e.getKey(), 0.0);
            if (runAvg >= median) {
                topTotal++;
                if (Boolean.TRUE.equals(e.getValue())) topPass++;
            } else {
                bottomTotal++;
                if (Boolean.TRUE.equals(e.getValue())) bottomPass++;
            }
        }
        if (topTotal == 0 || bottomTotal == 0) {
            return 0;
        }
        return (double) topPass / topTotal - (double) bottomPass / bottomTotal;
    }

    private Map<UUID, EvaluationRunItem> latestByItem(UUID runId) {
        Map<UUID, EvaluationRunItem> map = new LinkedHashMap<>();
        for (EvaluationRunItem item : runItemRepository.findLatestAttempts(runId)) {
            map.put(item.getItemId(), item);
        }
        return map;
    }

    private Double scoreOf(EvaluationRunItem item) {
        if (item == null || item.getAiScore() == null) {
            return null;
        }
        try {
            var node = objectMapper.readTree(item.getAiScore());
            return node.path("total_score").isNumber() ? node.path("total_score").asDouble() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 0 ? (sorted.get(mid - 1) + sorted.get(mid)) / 2 : sorted.get(mid);
    }

    private EvaluationDataset requireDataset(UUID datasetId) {
        EvaluationDataset dataset = datasetRepository.findById(datasetId)
                .orElseThrow(() -> new BusinessException(404, "题集不存在"));
        UUID company = requireCompany();
        if (dataset.getCompanyId() != null && !dataset.getCompanyId().equals(company)) {
            throw new BusinessException(404, "题集不存在");
        }
        return dataset;
    }

    private EvaluationRun requireRun(UUID runId) {
        EvaluationRun run = runRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(404, "评测批次不存在"));
        if (!requireCompany().equals(run.getCompanyId())) {
            throw new BusinessException(404, "评测批次不存在");
        }
        return run;
    }

    private UUID requireCompany() {
        return accessService.requireCompanyId();
    }

    private double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
