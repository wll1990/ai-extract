package com.aiextract.service.workflow;

import com.aiextract.exception.BusinessException;
import com.aiextract.model.WorkflowDefinition;
import com.aiextract.model.WorkflowEvent;
import com.aiextract.model.WorkflowNodeRun;
import com.aiextract.model.WorkflowRun;
import com.aiextract.repository.WorkflowDefinitionRepository;
import com.aiextract.repository.WorkflowEventRepository;
import com.aiextract.repository.WorkflowNodeRunRepository;
import com.aiextract.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 工作流运行的事务边界 —— 领取、落库、恢复都必须是代理调用（不能在 Engine 内自调用）。
 */
@Service
@RequiredArgsConstructor
public class WorkflowRunStore {

    private final WorkflowDefinitionRepository definitions;
    private final WorkflowRunRepository runs;
    private final WorkflowNodeRunRepository nodeRuns;
    private final WorkflowEventRepository events;
    private final ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    public WorkflowRun start(String workflowKey, String input, UUID userId, UUID companyId) {
        WorkflowDefinition definition = definitions
                .findFirstByWorkflowKeyAndStatusOrderByVersionDesc(workflowKey, "published")
                .orElseThrow(() -> new BusinessException(404, "工作流不存在或未发布"));
        Instant now = Instant.now();
        WorkflowRun run = WorkflowRun.builder()
                .id(UUID.randomUUID())
                .definitionId(definition.getId())
                .userId(userId)
                .companyId(companyId)
                .status("pending")
                .input(jsonText(input))
                .attempts(0)
                .eventSeq(0)
                .availableAt(now)
                .createdAt(now)
                .build();
        runs.save(run);
        appendEvent(run, "started", null);
        runs.save(run);
        return run;
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkflowRun claim(String workerId, int leaseSeconds) {
        Instant now = Instant.now();
        WorkflowRun run = runs.claimNext(now).orElse(null);
        if (run == null) {
            return null;
        }
        run.setLeaseOwner(workerId);
        run.setLeaseUntil(now.plusSeconds(leaseSeconds));
        run.setStatus("running");
        if (run.getStartedAt() == null) {
            run.setStartedAt(now);
        }
        return runs.save(run);
    }

    @Transactional(rollbackFor = Exception.class)
    public void persist(UUID runId, String workerId, NodeResult result, int maxAttempts) {
        WorkflowRun run = runs.findById(runId).orElse(null);
        if (run == null || !workerId.equals(run.getLeaseOwner())) {
            return;
        }
        Instant now = Instant.now();
        nodeRuns.save(WorkflowNodeRun.builder()
                .id(UUID.randomUUID())
                .runId(runId)
                .nodeKey(result.nodeKey())
                .nodeType(result.nodeType())
                .status(result.success() ? "succeeded" : "failed")
                .input(run.getInput())
                .output(result.output())
                .error(result.error())
                .attempt((run.getAttempts() == null ? 0 : run.getAttempts()) + 1)
                .startedAt(result.startedAt())
                .finishedAt(now)
                .build());

        run.setLeaseOwner(null);
        run.setLeaseUntil(null);
        if (result.success()) {
            run.setInput(result.output());
            run.setOutput(result.output());
            run.setError(null);
            // 重试预算按「当前节点连续失败次数」计算，成功即清零，
            // 否则前一个节点的失败会吃掉后续节点的重试机会。
            run.setAttempts(0);
            if (result.next() == null || result.next().isBlank() || "end".equals(result.next())) {
                run.setStatus("succeeded");
                run.setCurrentNode(null);
                run.setFinishedAt(now);
                appendEvent(run, "completed", result.nodeKey());
            } else {
                run.setStatus("pending");
                run.setCurrentNode(result.next());
                run.setAvailableAt(now);
                appendEvent(run, "node_completed", result.nodeKey());
            }
        } else {
            int attempts = (run.getAttempts() == null ? 0 : run.getAttempts()) + 1;
            run.setAttempts(attempts);
            run.setError(truncate(result.error()));
            if (attempts >= maxAttempts) {
                run.setStatus("failed");
                run.setFinishedAt(now);
                appendEvent(run, "failed", result.nodeKey());
            } else {
                run.setStatus("pending");
                run.setAvailableAt(now.plusSeconds(backoff(attempts)));
                appendEvent(run, "retry", result.nodeKey());
            }
        }
        runs.save(run);
    }

    @Transactional(readOnly = true)
    public WorkflowRun get(UUID runId, UUID userId) {
        WorkflowRun run = runs.findById(runId)
                .orElseThrow(() -> new BusinessException(404, "工作流运行不存在"));
        if (!run.getUserId().equals(userId)) {
            throw new BusinessException(404, "工作流运行不存在");
        }
        return run;
    }

    @Transactional(rollbackFor = Exception.class)
    public void resume(UUID runId, UUID userId) {
        WorkflowRun run = get(runId, userId);
        if (!"waiting".equals(run.getStatus())) {
            throw new BusinessException(400, "当前状态不可恢复");
        }
        run.setStatus("pending");
        run.setResumeToken(null);
        run.setWaitingUntil(null);
        run.setAvailableAt(Instant.now());
        runs.save(run);
    }

    private void appendEvent(WorkflowRun run, String type, String nodeKey) {
        int seq = (run.getEventSeq() == null ? 0 : run.getEventSeq()) + 1;
        run.setEventSeq(seq);
        events.save(WorkflowEvent.builder()
                .runId(run.getId())
                .seq((long) seq)
                .type(type)
                .payload(nodeKey == null ? "{}" : jsonText(nodeKey))
                .createdAt(Instant.now())
                .build());
    }

    private String jsonText(String text) {
        try {
            return objectMapper.writeValueAsString(Map.of("text", text == null ? "" : text));
        } catch (Exception e) {
            return "{\"text\":\"\"}";
        }
    }

    private static long backoff(int attempts) {
        return Math.min(60L, 1L << Math.min(attempts, 6));
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }

    public record NodeResult(String nodeKey, String nodeType, boolean success,
                             String output, String next, String error, Instant startedAt) {

        public static NodeResult success(String nodeKey, String nodeType, String output,
                                         String next, Instant startedAt) {
            return new NodeResult(nodeKey, nodeType, true, output, next, null, startedAt);
        }

        public static NodeResult failed(String nodeKey, String nodeType, String error,
                                        Instant startedAt) {
            return new NodeResult(nodeKey, nodeType, false, null, null, error, startedAt);
        }
    }
}
