package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.config.DomainConfig;
import com.aiextract.config.DomainConfigLoader;
import com.aiextract.model.Skill;
import com.aiextract.repository.SkillRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@RestController
/**
 * @author AI Extract Team
 */
@RequiredArgsConstructor
public class DomainController {

    private final DomainConfigLoader domainLoader;
    private final SkillRepository skillRepository;
    private final ObjectMapper objectMapper;

    @GetMapping("/api/domains")
    public ApiResponse<List<Map<String, Object>>> listDomains() {
        try {
            ClassPathResource resource = new ClassPathResource("domain/_tree.json");
            String json = resource.getContentAsString(StandardCharsets.UTF_8);
            List<Map<String, Object>> tree = objectMapper.readValue(json, List.class);
            return ApiResponse.success(tree);
        } catch (Exception e) {
            log.error("加载领域树失败", e);
            return ApiResponse.success(List.of());
        }
    }

    @GetMapping("/api/skills/{skillId}/domain-config")
    public ApiResponse<Map<String, Object>> getDomainConfig(@PathVariable UUID skillId) {
        Skill skill = skillRepository.findById(skillId).orElse(null);
        if (skill == null) {

            return ApiResponse.error(404, "分身不存在");

        }

        String domainId = domainLoader.resolveDomain(skill);
        if (domainId == null) {

            return ApiResponse.error(400, "分身未设置领域");

        }

        DomainConfig config = domainLoader.load(domainId);
        if (config == null || config.getDomain() == null || config.getChat() == null || config.getChat().getModes() == null) {
            return ApiResponse.error(500, "领域配置加载失败");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("domainId", domainId);
        result.put("domainName", config.getDomain().getName());
        result.put("roleLabel", config.getDomain().getRoleLabel());
        result.put("counterpartyLabel", config.getDomain().getCounterpartyLabel());
        result.put("modes", config.getChat().getModes());
        return ApiResponse.success(result);
    }
}
