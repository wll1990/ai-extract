package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.config.CurrentUser;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.WorkflowRun;
import com.aiextract.service.workflow.WorkflowEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowEngine engine;

    public record StartRequest(String workflowKey, String input) {
    }

    public record RunView(UUID id, String status, String currentNode, String input,
                          String output, String error, Instant createdAt, Instant finishedAt) {
        static RunView of(WorkflowRun run) {
            return new RunView(run.getId(), run.getStatus(), run.getCurrentNode(), run.getInput(),
                    run.getOutput(), run.getError(), run.getCreatedAt(), run.getFinishedAt());
        }
    }

    @PostMapping("/runs")
    public ApiResponse<RunView> start(@RequestBody StartRequest request) {
        UUID userId = requireUser();
        if (request.workflowKey() == null || request.workflowKey().isBlank()) {
            throw new BusinessException(400, "workflowKey 不能为空");
        }
        WorkflowRun run = engine.start(request.workflowKey(), request.input(), userId,
                CurrentUser.homeCompanyId());
        return ApiResponse.success(RunView.of(run));
    }

    @GetMapping("/runs/{id}")
    public ApiResponse<RunView> get(@PathVariable UUID id) {
        return ApiResponse.success(RunView.of(engine.get(id, requireUser())));
    }

    @PostMapping("/runs/{id}/resume")
    public ApiResponse<Void> resume(@PathVariable UUID id) {
        engine.resume(id, requireUser());
        return ApiResponse.success();
    }

    private UUID requireUser() {
        UUID userId = CurrentUser.id();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        return userId;
    }
}
