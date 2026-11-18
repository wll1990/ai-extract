package com.aiextract.config;

import com.aiextract.model.WorkflowDefinition;
import com.aiextract.repository.WorkflowDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * 首次启动注册示例工作流：素材 → 萃取 → 质量门禁 → 报告。
 *
 * <p>只插入一次（workflowKey + version 唯一）；后续可通过管理端替换 graph。</p>
 */
@Component
@RequiredArgsConstructor
public class WorkflowBootstrap implements ApplicationRunner {

    private static final String WORKFLOW_KEY = "material-to-report";
    private static final int VERSION = 1;

    private static final String GRAPH = """
            {
              "start": "extract",
              "nodes": [
                {"key":"extract","type":"llm","prompt":"请从以下素材中萃取可复用的销售经验要点，输出条目化结果：\\n{{input}}","next":"quality"},
                {"key":"quality","type":"pass","next":"report"},
                {"key":"report","type":"llm","prompt":"请把以下要点整理成结构化报告，包含结论、方法、案例与行动建议：\\n{{input}}","next":"end"},
                {"key":"end","type":"end"}
              ]
            }
            """;

    private final WorkflowDefinitionRepository definitions;

    @Override
    public void run(ApplicationArguments args) {
        if (definitions.existsByWorkflowKeyAndVersion(WORKFLOW_KEY, VERSION)) {
            return;
        }
        definitions.save(WorkflowDefinition.builder()
                .id(UUID.randomUUID())
                .workflowKey(WORKFLOW_KEY)
                .version(VERSION)
                .name("素材萃取到报告")
                .graph(GRAPH)
                .status("published")
                .createdAt(Instant.now())
                .build());
    }
}
