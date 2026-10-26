package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.config.Permission;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 导航菜单下发 — 前端菜单与后端 SecurityConfig 用同一份权限码，消除 drift。
 *
 * <p>后端按当前登录用户权限过滤后返回，前端不再维护 route→permission 映射。</p>
 */
@RestController
@RequestMapping("/config")
public class NavConfigController {

    private record NavItem(String path, String label, String permission) {}

    private static final List<NavItem> NAV = List.of(
        new NavItem("/skills", "分身广场", Permission.SKILL_USE),
        new NavItem("/space/me", "我的空间", Permission.SPACE_OWN),
        new NavItem("/spaces", "空间总览", Permission.USER_MANAGE),
        new NavItem("/interview/create", "销冠访谈", Permission.SKILL_USE),
        new NavItem("/explore", "经验广场", Permission.SKILL_USE),
        new NavItem("/workbench", "我的工作台", Permission.SKILL_USE),
        new NavItem("/admin", "工作台", Permission.DASHBOARD_VIEW),
        new NavItem("/admin/insights", "数据看板", Permission.DASHBOARD_VIEW),
        new NavItem("/admin/tuning", "分身调优", Permission.SKILL_TUNING),
        new NavItem("/admin/users", "用户管理", Permission.USER_MANAGE),
        new NavItem("/admin/skills", "分身管理", Permission.SKILL_MANAGE),
        new NavItem("/admin/materials", "素材管理", Permission.MATERIAL_MANAGE),
        new NavItem("/admin/experts", "萃取师经验库", Permission.EXPERT_MANAGE),
        new NavItem("/admin/conversations", "对话历史", Permission.CONVERSATION_VIEW),
        new NavItem("/admin/coverage", "场景地图", Permission.SCENE_COVERAGE),
        new NavItem("/admin/grains", "颗粒管理", Permission.GRAIN_MANAGE),
        new NavItem("/admin/organization-skills", "组织分身", Permission.ORG_SKILL_MANAGE),
        new NavItem("/admin/im-bots", "分身接入", Permission.ORG_SKILL_MANAGE),
        new NavItem("/admin/token-usage", "Token 统计", Permission.TOKEN_VIEW_COMPANY),
        new NavItem("/admin/im", "IM管理", Permission.IM_MANAGE),
        new NavItem("/admin/partners", "合作方管理", Permission.PARTNER_MANAGE),
        new NavItem("/admin/companies", "企业合作", Permission.COMPANY_MANAGE)
    );

    @GetMapping("/nav")
    public ApiResponse<List<Map<String, String>>> nav() {
        Set<String> perms = SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        List<Map<String, String>> items = NAV.stream()
                .filter(i -> perms.contains(i.permission()))
                .map(i -> Map.of("path", i.path(), "label", i.label(), "permission", i.permission()))
                .collect(Collectors.toList());
        return ApiResponse.success(items);
    }
}
