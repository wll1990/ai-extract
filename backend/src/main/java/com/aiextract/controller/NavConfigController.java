package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.config.Permission;
import com.aiextract.config.TokenContext;
import com.aiextract.model.User;
import com.aiextract.repository.UserRepository;
import com.aiextract.service.NavBadgeService;
import lombok.RequiredArgsConstructor;
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
 * <p>后端按当前登录用户权限与角色过滤后返回，前端不再维护 route→permission 映射。
 * 菜单按业务域（domain）分组：顶栏切域，侧栏只展示当前域的 5–6 项。</p>
 */
@RestController
@RequestMapping("/config")
@RequiredArgsConstructor
public class NavConfigController {

    private final UserRepository userRepository;
    private final NavBadgeService navBadgeService;

    /**
     * @param roles null 表示所有角色可见；非 null 时只对指定角色下发。
     *              用于同一路径在不同角色下出现在不同域（如员工「上传素材」）。
     */
    private record NavItem(String path, String label, String permission, String domain, Set<String> roles) {
        static NavItem all(String path, String label, String permission, String domain) {
            return new NavItem(path, label, permission, domain, null);
        }

        static NavItem only(String path, String label, String permission, String domain, String... roles) {
            return new NavItem(path, label, permission, domain, Set.of(roles));
        }
    }

    private static final String DAILY = "daily";
    private static final String SALES = "sales";
    private static final String PRODUCTION = "production";
    private static final String KNOWLEDGE = "knowledge";
    private static final String MANAGE = "manage";
    private static final String PLATFORM = "platform";

    private static final List<NavItem> NAV = List.of(
        // ── 日常 ──
        NavItem.all("/skills", "分身广场", Permission.SKILL_USE, DAILY),
        NavItem.all("/workbench", "我的工作台", Permission.SKILL_USE, DAILY),
        NavItem.all("/explore", "经验广场", Permission.SKILL_USE, DAILY),
        NavItem.all("/interview/create", "销冠访谈", Permission.SKILL_USE, DAILY),
        // 属主管理自己的分身（含未发布），也是试问台的入口
        NavItem.all("/my/skills", "我的分身", Permission.SPACE_OWN, DAILY),
        NavItem.all("/space/me", "我的空间", Permission.SPACE_OWN, DAILY),
        // 员工只有素材权限，给一个直达入口，不暴露整组「分身生产」
        NavItem.only("/admin/materials", "上传素材", Permission.MATERIAL_MANAGE, DAILY, "employee", "c_user", "c_partner"),

        // ── 销售与培训 ──
        NavItem.all("/sales/dashboard", "我的销售看板", Permission.SALES_REVIEW, SALES),
        NavItem.all("/sales/insights", "团队共性问题", Permission.SALES_REVIEW_VIEW_ALL, SALES),
        NavItem.all("/sales/reviews", "销售复盘", Permission.SALES_REVIEW, SALES),
        NavItem.all("/sales/leads", "线索", Permission.SALES_LEAD_MANAGE, SALES),
        NavItem.all("/sales/tasks", "跟进任务", Permission.SALES_LEAD_MANAGE, SALES),
        NavItem.all("/sales/coach/ledger", "教练台账", Permission.SALES_REVIEW, SALES),
        NavItem.all("/training", "我的培训", Permission.TRAINING_LEARN, SALES),
        NavItem.all("/admin/training/plans", "培训计划", Permission.TRAINING_MANAGE, SALES),

        // ── 分身生产 ──
        NavItem.all("/admin/skills", "分身管理", Permission.SKILL_MANAGE, PRODUCTION),
        NavItem.only("/admin/materials", "素材管理", Permission.MATERIAL_MANAGE, PRODUCTION,
                "company_admin", "super_admin"),
        NavItem.all("/admin/grains", "经验颗粒", Permission.GRAIN_MANAGE, PRODUCTION),
        NavItem.all("/admin/grain-corrections", "纠错建议", Permission.GRAIN_MANAGE, PRODUCTION),
        NavItem.all("/admin/tuning", "分身调优", Permission.SKILL_TUNING, PRODUCTION),
        NavItem.all("/admin/organization-skills", "组织分身", Permission.ORG_SKILL_MANAGE, PRODUCTION),
        // 页面实际调用 /im/channels/**，权限必须与接口一致（IM_MANAGE），否则 company_admin 点进去 403
        // 「IM 渠道配置」(/admin/im) 已从菜单移除：渠道生命周期统一走发布流，
        // 该页只保留 URL 直达用于排查，避免两套入口互相覆盖
        NavItem.all("/admin/im-bots", "IM 分身接入", Permission.IM_MANAGE, PRODUCTION),

        // ── 知识与洞察 ──
        NavItem.all("/admin/knowledge", "知识库", Permission.KNOWLEDGE_MANAGE, KNOWLEDGE),
        NavItem.all("/admin/cases", "案例库", Permission.KNOWLEDGE_MANAGE, KNOWLEDGE),
        // 一线销售提交经验草稿（审批仍在管理端）
        NavItem.all("/knowledge/submissions", "提交知识", Permission.KNOWLEDGE_SUBMIT, KNOWLEDGE),
        NavItem.all("/cases/submissions", "提交案例", Permission.KNOWLEDGE_SUBMIT, KNOWLEDGE),
        NavItem.all("/admin/experts", "专家经验", Permission.EXPERT_MANAGE, KNOWLEDGE),
        NavItem.all("/admin/coverage", "场景地图", Permission.SCENE_COVERAGE, KNOWLEDGE),
        NavItem.all("/admin/insights", "数据看板", Permission.DASHBOARD_VIEW, KNOWLEDGE),
        NavItem.all("/admin/evaluations", "评测中心", Permission.EVAL_MANAGE, KNOWLEDGE),
        // AI 草稿审核是内容工厂的筛选视图，不单独占一级菜单；页面仍可从内容工厂进入
        NavItem.all("/admin/content", "内容工厂", Permission.KNOWLEDGE_MANAGE, KNOWLEDGE),

        // ── 组织与设置 ──
        NavItem.all("/admin", "运营总览", Permission.DASHBOARD_VIEW, MANAGE),
        NavItem.all("/admin/users", "用户管理", Permission.USER_MANAGE, MANAGE),
        NavItem.all("/spaces", "成员空间", Permission.USER_MANAGE, MANAGE),
        NavItem.all("/admin/conversations", "对话历史", Permission.CONVERSATION_VIEW, MANAGE),
        NavItem.all("/admin/token-usage", "Token 统计", Permission.TOKEN_VIEW_COMPANY, MANAGE),
        NavItem.all("/admin/settings/ai-model", "AI 模型配置", Permission.AI_MODEL_MANAGE, MANAGE),

        // ── 平台（仅超管） ──
        NavItem.all("/admin/partners", "合作方管理", Permission.PARTNER_MANAGE, PLATFORM),
        NavItem.all("/admin/companies", "企业合作", Permission.COMPANY_MANAGE, PLATFORM),
        NavItem.all("/admin/demo-requests", "预约演示", Permission.COMPANY_MANAGE, PLATFORM)
    );

    @GetMapping("/nav")
    public ApiResponse<List<Map<String, String>>> nav() {
        Set<String> perms = SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        String role = currentRole();

        List<Map<String, String>> items = NAV.stream()
                .filter(i -> perms.contains(i.permission()))
                .filter(i -> i.roles() == null || i.roles().contains(role))
                .map(i -> Map.of(
                        "path", i.path(),
                        "label", i.label(),
                        "permission", i.permission(),
                        "domain", i.domain()))
                .collect(Collectors.toList());
        return ApiResponse.success(items);
    }

    /** 导航待办红点：域级汇总 + 菜单项级数字 */
    @GetMapping("/nav/badges")
    public ApiResponse<Map<String, Object>> badges() {
        return ApiResponse.success(navBadgeService.getBadges());
    }

    /** 角色用于少数「同路径不同角色」的菜单项；查不到时返回空串，不影响权限过滤。 */
    private String currentRole() {
        var userId = TokenContext.get();
        if (userId == null) return "";
        return userRepository.findById(userId).map(User::getRole).orElse("");
    }
}
