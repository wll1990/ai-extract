package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.Space;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.SpaceRepository;
import com.aiextract.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * C端颗粒操作控制器 — 审核页保留/废弃/编辑颗粒。
 *
 * <p>属主校验：grain → space → space.isOwnedBy(currentUserId)。
 * B端用户同样可用（SecurityConfig /grains/** = authenticated）。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-24
 */
@Slf4j
@RestController
@RequestMapping("/grains")
@RequiredArgsConstructor
public class GrainController {

    private final ExperienceGrainRepository grainRepository;
    private final SpaceRepository spaceRepository;
    private final JwtUtil jwtUtil;

    private UUID extractUserId() {
        String token = (String) SecurityContextHolder.getContext().getAuthentication().getCredentials();
        return jwtUtil.getUserIdFromToken(token);
    }

    private ExperienceGrain requireOwnGrain(UUID grainId, UUID userId) {
        ExperienceGrain grain = grainRepository.findById(grainId)
            .orElseThrow(() -> new BusinessException(404, "颗粒不存在"));
        Space space = spaceRepository.findById(grain.getSpaceId())
            .orElseThrow(() -> new BusinessException(404, "空间不存在"));
        if (!space.isOwnedBy(userId)) {
            throw new BusinessException(403, "无权操作");
        }
        return grain;
    }

    /**
     * 更新颗粒状态（保留=active / 废弃=deprecated）。
     */
    @PutMapping("/{grainId}")
    public ApiResponse<Map<String, Object>> updateGrain(
            @PathVariable UUID grainId,
            @RequestBody Map<String, String> body) {
        UUID userId = extractUserId();
        ExperienceGrain grain = requireOwnGrain(grainId, userId);

        if (body.containsKey("status")) {
            String newStatus = body.get("status");
            if ("active".equals(newStatus) || "deprecated".equals(newStatus)) {
                grain.setStatus(newStatus);
            } else {
                throw new BusinessException(400, "status 仅支持 active/deprecated");
            }
        }
        if (body.containsKey("expertThought")) {
            grain.setExpertThought(body.get("expertThought"));
        }
        if (body.containsKey("standardScript")) {
            grain.setStandardScript(body.get("standardScript"));
        }
        if (body.containsKey("sceneTag")) {
            grain.setSceneTag(body.get("sceneTag"));
        }
        if (body.containsKey("sceneDescription")) {
            grain.setSceneDescription(body.get("sceneDescription"));
        }
        if (body.containsKey("commonMistakes")) {
            grain.setCommonMistakes(body.get("commonMistakes"));
        }

        grainRepository.save(grain);
        log.info("颗粒已更新 grainId={} status={}", grainId, grain.getStatus());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", grain.getId().toString());
        result.put("status", grain.getStatus());
        result.put("sceneTag", grain.getSceneTag());
        result.put("expertThought", grain.getExpertThought());
        result.put("standardScript", grain.getStandardScript());
        return ApiResponse.success(result);
    }
}
