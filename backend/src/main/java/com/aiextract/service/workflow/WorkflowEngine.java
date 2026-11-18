package com.aiextract.service.workflow;

import com.aiextract.config.TokenContext;
import com.aiextract.model.WorkflowDefinition;
import com.aiextract.model.WorkflowRun;
import com.aiextract.repository.WorkflowDefinitionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 轻量工作流执行器。
 *
 * <p>租约领取与结果落库在 {@link WorkflowRunStore} 的独立事务中完成；
 * LLM 调用在事务外执行，避免长事务持有连接。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowEngine {

    private final WorkflowDefinitionRepository definitions;
    private final WorkflowRunStore store;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    @Value("${app.workflow.worker-id:}")
    private String configuredWorkerId;
    @Value("${app.workflow.max-attempts:3}")
    private int maxAttempts;
    @Value("${app.workflow.lease-seconds:60}")
    private int leaseSeconds;
    @Value("${app.workflow.runs-per-tick:2}")
    private int runsPerTick;

    private String workerId;

    @PostConstruct
    void init() {
        workerId = configuredWorkerId != null && !configuredWorkerId.isBlank()
                ? configuredWorkerId
                : "wf-" + ProcessHandle.current().pid();
    }

    public WorkflowRun start(String workflowKey, String input, UUID userId, UUID companyId) {
        return store.start(workflowKey, input, userId, companyId);
    }

    public WorkflowRun get(UUID runId, UUID userId) {
        return store.get(runId, userId);
    }

    public void resume(UUID runId, UUID userId) {
        store.resume(runId, userId);
    }

    @Scheduled(fixedDelayString = "${app.workflow.worker-interval-ms:5000}",
            initialDelayString = "${app.workflow.worker-initial-delay-ms:15000}")
    public void tick() {
        for (int i = 0; i < runsPerTick; i++) {
            try {
                WorkflowRun run = store.claim(workerId, leaseSeconds);
                if (run == null) {
                    return;
                }
                WorkflowRunStore.NodeResult result = execute(run);
                store.persist(run.getId(), workerId, result, maxAttempts);
            } catch (Exception e) {
                log.warn("workflow tick failed: {}", e.toString());
            }
        }
    }

    private WorkflowRunStore.NodeResult execute(WorkflowRun run) {
        Instant started = Instant.now();
        String nodeKey = run.getCurrentNode();
        String nodeType = "unknown";
        try {
            WorkflowDefinition definition = definitions.findById(run.getDefinitionId())
                    .orElseThrow(() -> new IllegalStateException("工作流定义不存在"));
            JsonNode graph = objectMapper.readTree(definition.getGraph());
            if (nodeKey == null) {
                nodeKey = graph.path("start").asText();
            }
            JsonNode node = findNode(graph, nodeKey);
            if (node == null) {
                return WorkflowRunStore.NodeResult.failed(nodeKey, nodeType, "节点不存在", started);
            }
            nodeType = node.path("type").asText();
            if ("end".equals(nodeType)) {
                return WorkflowRunStore.NodeResult.success(nodeKey, nodeType, run.getInput(), null, started);
            }
            String output;
            if ("pass".equals(nodeType)) {
                output = run.getInput();
            } else if ("llm".equals(nodeType)) {
                output = callLlm(node.path("prompt").asText(""), run);
            } else {
                return WorkflowRunStore.NodeResult.failed(nodeKey, nodeType,
                        "不支持的节点类型: " + nodeType, started);
            }
            String next = node.path("next").asText(null);
            return WorkflowRunStore.NodeResult.success(nodeKey, nodeType, output, next, started);
        } catch (Exception e) {
            return WorkflowRunStore.NodeResult.failed(nodeKey, nodeType, e.getMessage(), started);
        }
    }

    private String callLlm(String template, WorkflowRun run) throws Exception {
        String text = objectMapper.readTree(run.getInput()).path("text").asText("");
        String prompt = template.replace("{{input}}", text);
        String result = TokenContext.callWith(run.getUserId(), run.getCompanyId(),
                () -> chatClient.prompt().user(prompt).call().content());
        return objectMapper.writeValueAsString(Map.of("text", result == null ? "" : result));
    }

    private JsonNode findNode(JsonNode graph, String key) {
        for (JsonNode node : graph.path("nodes")) {
            if (key.equals(node.path("key").asText())) {
                return node;
            }
        }
        return null;
    }
}
