package com.aiextract.controller;

import com.aiextract.exception.BusinessException;
import com.aiextract.common.ApiResponse;
import com.aiextract.model.Tool;
import com.aiextract.repository.ToolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import com.aiextract.common.ErrorMessages;

/**
 * 工具控制器（补全缺失的25-26号接口）
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Slf4j
@RestController
@RequestMapping("/tools")
@RequiredArgsConstructor
public class ToolController {

    private final ToolRepository toolRepository;

    /**
     * 获取资料库列表
     *
     * @param spaceId 空间ID（可选）
     * @param type    工具类型（可选）
     * @return 工具列表
     */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> getTools(
            @RequestParam(required = false) String spaceId,
            @RequestParam(required = false) String type) {
        List<Tool> tools;
        if (spaceId != null && !spaceId.isEmpty()) {
            tools = toolRepository.findBySpaceId(UUID.fromString(spaceId));
        } else if (type != null && !type.isEmpty()) {
            tools = toolRepository.findByType(type);
        } else {
            tools = toolRepository.findAll(PageRequest.of(0, 200)).getContent();
        }

        List<Map<String, Object>> list = new ArrayList<>();
        for (Tool t : tools) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", t.getId().toString());
            item.put("type", t.getType());
            item.put("name", t.getName());
            item.put("fileUrl", t.getFileUrl());
            if (t.getSpaceId() != null) item.put("spaceId", t.getSpaceId().toString());
            if (t.getReportId() != null) item.put("reportId", t.getReportId().toString());
            list.add(item);
        }
        return ApiResponse.success(list);
    }

    /**
     * 下载工具资料
     *
     * @param toolId 工具ID
     * @return 文件下载信息
     */
    @GetMapping("/{toolId}/download")
    public ApiResponse<Map<String, Object>> downloadTool(@PathVariable String toolId) {
        Tool t = toolRepository.findById(UUID.fromString(toolId))
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.TOOL_NOT_FOUND));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileUrl", t.getFileUrl());
        result.put("fileName", t.getName());
        return ApiResponse.success(result);
    }
}
