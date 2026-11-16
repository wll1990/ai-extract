package com.aiextract.config;

import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security安全配置
 *
 * <p>配置JWT无状态认证，定义接口访问权限，禁用CSRF和Session。
 * 使用BCrypt作为密码加密算法。所有权限校验通过 {@link Permission} 权限码。
 * 规则顺序敏感：具体路径在前，通配路径在后。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> {})
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 未认证/凭证过期 → 401（前端据此清凭证并跳登录）；
                // 已认证但权限不足 → 403。默认实现会把两者都返回 403，
                // 导致过期 token 无法触发前端重新登录。
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> {
                            res.setStatus(401);
                            res.setContentType("application/json;charset=UTF-8");
                            res.getWriter().write("{\"code\":401,\"message\":\"登录已失效，请重新登录\"}");
                        })
                        .accessDeniedHandler((req, res, e) -> {
                            res.setStatus(403);
                            res.setContentType("application/json;charset=UTF-8");
                            res.getWriter().write("{\"code\":403,\"message\":\"无权限访问\"}");
                        }))

                .authorizeHttpRequests(auth -> auth
                        // 异步分发（SSE等）
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()

                        // ── B 端认证：无需认证 ──
                        .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/register/with-code").permitAll()

                        // ── 公开接口 ──
                        .requestMatchers("/public/reports/**").permitAll()
                        .requestMatchers("/public/**").permitAll()

                        // ── C 端认证 ──
                        .requestMatchers(HttpMethod.POST, "/c/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/c/auth/register").hasAuthority(RolePermissions.C_GUEST)
                        .requestMatchers(HttpMethod.POST, "/c/auth/register/new").permitAll()
                        .requestMatchers(HttpMethod.GET, "/c/auth/me").authenticated()

                        // ── Swagger / 健康检查 ──
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()

                        // ── 语音识别（录音后一次性识别，任意有效 JWT）──
                        .requestMatchers(HttpMethod.POST, "/stt/recognize").authenticated()

                        // ── IM 回调：无需 JWT ──
                        .requestMatchers("/im/*/callback").permitAll()

                        // ── IM 内部接口（传输层 ↔ 后端，HMAC 签名在 Controller 校验） ──
                        .requestMatchers("/internal/im/**").permitAll()

                        // ── 前端埋点（fire-and-forget）──
                        .requestMatchers(HttpMethod.POST, "/analytics/event").permitAll()

                        // ── 登录状态检查 ──
                        .requestMatchers(HttpMethod.POST, "/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/auth/me").authenticated()
                        .requestMatchers(HttpMethod.POST, "/auth/avatar").authenticated()
                        .requestMatchers(HttpMethod.POST, "/auth/change-password").authenticated()

                        // ── 对内分享 ──
                        .requestMatchers(HttpMethod.GET, "/i/*/info").permitAll()
                        .requestMatchers("/i/**").authenticated()

                        // ── 菜单下发（任意登录态，后端按权限过滤）──
                        .requestMatchers(HttpMethod.GET, "/config/nav", "/config/nav/badges").authenticated()

                        // ═══════════════════════════════════════════════════════
                        // 管理后台 — Platform 级（仅 super_admin 拥有对应权限码）
                        // 具体路径在前，通用 /admin/** 在后
                        // ═══════════════════════════════════════════════════════
                        .requestMatchers("/admin/demo-requests/**").hasAuthority(Permission.COMPANY_MANAGE)
                        .requestMatchers("/admin/companies/**").hasAuthority(Permission.COMPANY_MANAGE)
                        .requestMatchers("/admin/partners/**").hasAuthority(Permission.PARTNER_MANAGE)
                        .requestMatchers("/admin/im/**").hasAuthority(Permission.IM_MANAGE)
                        .requestMatchers("/admin/token-usage/**").hasAuthority(Permission.TOKEN_VIEW_COMPANY)
                        .requestMatchers("/admin/insights/**").hasAuthority(Permission.DASHBOARD_VIEW)
                        .requestMatchers("/admin/health/**").hasAuthority(Permission.DASHBOARD_VIEW)
                        .requestMatchers("/admin/notifications/**").hasAuthority(Permission.DASHBOARD_VIEW)
                        // 企业 AI Key：company_admin / super_admin（AI_MODEL_MANAGE）
                        .requestMatchers("/admin/company/ai-model/**").hasAuthority(Permission.AI_MODEL_MANAGE)
                        .requestMatchers("/admin/company/ai-model").hasAuthority(Permission.AI_MODEL_MANAGE)
                        // 企业管理员自助生成员工注册码（仅本企业、仅员工角色）
                        .requestMatchers("/admin/company/register-codes/**").hasAuthority(Permission.USER_MANAGE)
                        // 销售复盘：细粒度规则必须先于 /sales/** 通配
                        .requestMatchers(HttpMethod.GET, "/sales/coach/ledger/**").hasAuthority(Permission.SALES_REVIEW)
                        .requestMatchers("/sales/coach/requests/**").hasAuthority(Permission.SALES_REVIEW)
                        .requestMatchers("/sales/leads/**").hasAuthority(Permission.SALES_LEAD_MANAGE)
                        .requestMatchers("/sales/tasks/**").hasAuthority(Permission.SALES_LEAD_MANAGE)
                        .requestMatchers("/sales/**").hasAnyAuthority(
                                Permission.SALES_REVIEW, Permission.SALES_REVIEW_VIEW_ALL,
                                Permission.SALES_LEAD_MANAGE)
                        .requestMatchers("/admin/organization-skills/**").hasAuthority(Permission.ORG_SKILL_MANAGE)
                        .requestMatchers("/admin/grains/**").hasAuthority(Permission.GRAIN_MANAGE)
                        .requestMatchers("/admin/grain-corrections/**").hasAuthority(Permission.GRAIN_MANAGE)
                        .requestMatchers("/admin/knowledge/**").hasAuthority(Permission.KNOWLEDGE_MANAGE)
                        .requestMatchers("/admin/cases/**").hasAuthority(Permission.KNOWLEDGE_MANAGE)
                        // AI 草稿审核台：列表知识/评测管理均可看；考题审核需评测管理权限
                        .requestMatchers(HttpMethod.GET, "/admin/content/review")
                        .hasAnyAuthority(Permission.KNOWLEDGE_MANAGE, Permission.EVAL_MANAGE)
                        .requestMatchers("/admin/content/review/eval-item/**").hasAuthority(Permission.EVAL_MANAGE)
                        .requestMatchers("/admin/content/review/**").hasAuthority(Permission.KNOWLEDGE_MANAGE)
                        // 评测分析与异步任务：评测管理也要能读/轮询
                        .requestMatchers(HttpMethod.GET, "/admin/content/evaluation/**", "/admin/content/jobs/**")
                        .hasAnyAuthority(Permission.KNOWLEDGE_MANAGE, Permission.EVAL_MANAGE)
                        .requestMatchers(HttpMethod.POST, "/admin/content/evaluation/**")
                        .hasAnyAuthority(Permission.KNOWLEDGE_MANAGE, Permission.EVAL_MANAGE)
                        .requestMatchers("/admin/content/**").hasAuthority(Permission.KNOWLEDGE_MANAGE)
                        .requestMatchers(HttpMethod.POST, "/admin/evaluations/datasets/*/review").hasAuthority(Permission.EVAL_REVIEW)
                        // 复核与定稿属审核职责，必须先于 /admin/evaluations/** 的通配规则（规则顺序敏感）
                        .requestMatchers(HttpMethod.POST, "/admin/evaluations/results/*/reviews").hasAuthority(Permission.EVAL_REVIEW)
                        .requestMatchers(HttpMethod.POST, "/admin/evaluations/runs/*/finalize").hasAuthority(Permission.EVAL_REVIEW)
                        .requestMatchers("/admin/evaluations/**").hasAuthority(Permission.EVAL_MANAGE)
                        // 复核/认证/进度属复核职责（导师可做），必须先于 TRAINING_MANAGE 通配规则
                        .requestMatchers(HttpMethod.POST, "/admin/training/attempts/*/reviews").hasAuthority(Permission.TRAINING_REVIEW)
                        .requestMatchers(HttpMethod.POST, "/admin/training/attempts/*/regrade").hasAuthority(Permission.TRAINING_REVIEW)
                        .requestMatchers(HttpMethod.POST, "/admin/training/assignments/*/certify").hasAuthority(Permission.TRAINING_REVIEW)
                        .requestMatchers(HttpMethod.POST, "/admin/training/assignments/*/certification/revoke").hasAuthority(Permission.TRAINING_REVIEW)
                        .requestMatchers(HttpMethod.POST, "/admin/training/assignments/*/remediation").hasAuthority(Permission.TRAINING_REVIEW)
                        .requestMatchers(HttpMethod.POST, "/admin/training/assignments/*/deadline").hasAuthority(Permission.TRAINING_REVIEW)
                        // 导师的异常出口：免修、AI 误判重做、批次效果
                        .requestMatchers(HttpMethod.POST, "/admin/training/assignments/*/reopen").hasAuthority(Permission.TRAINING_REVIEW)
                        .requestMatchers(HttpMethod.POST, "/admin/training/assignments/*/records/*/waive").hasAuthority(Permission.TRAINING_REVIEW)
                        .requestMatchers(HttpMethod.GET, "/admin/training/cohorts/*/effect").hasAuthority(Permission.TRAINING_REVIEW)
                        // 改绑属高风险操作，仅管理权限且必须 force + 理由（Service 校验）
                        .requestMatchers(HttpMethod.POST, "/admin/training/assignments/*/rebind").hasAuthority(Permission.TRAINING_MANAGE)
                        .requestMatchers(HttpMethod.GET, "/admin/training/cohorts/*/progress").hasAuthority(Permission.TRAINING_REVIEW)
                        // 复核要能打开尝试详情与学员的尝试列表（导师没有 TRAINING_MANAGE）
                        .requestMatchers(HttpMethod.GET, "/admin/training/attempts/*").hasAuthority(Permission.TRAINING_REVIEW)
                        .requestMatchers(HttpMethod.GET, "/admin/training/assignments/*/attempts").hasAuthority(Permission.TRAINING_REVIEW)
                        // 导师要能看到自己带的批次；是否本人批次由 Service 数据级判定
                        .requestMatchers(HttpMethod.GET, "/admin/training/cohorts/*").hasAuthority(Permission.TRAINING_REVIEW)
                        // 导师工作台：有复核权限即可访问，范围由 Service 判定（导师只看自己批次）
                        .requestMatchers(HttpMethod.GET, "/admin/training/workbench").hasAuthority(Permission.TRAINING_REVIEW)
                        // 导出属管理职责（导师只导自己批次由 Service 校验）
                        .requestMatchers(HttpMethod.POST, "/admin/training/cohorts/*/export").hasAuthority(Permission.TRAINING_MANAGE)
                        .requestMatchers("/admin/training/exports/**").hasAuthority(Permission.TRAINING_MANAGE)
                        .requestMatchers("/admin/training/**").hasAuthority(Permission.TRAINING_MANAGE)
                        // 学员端统一挂 /me：userId 只能来自 JWT，从路由层消除越权面
                        .requestMatchers("/me/training/**").hasAuthority(Permission.TRAINING_LEARN)
                        .requestMatchers("/me/knowledge/**", "/me/cases/**")
                        .hasAuthority(Permission.KNOWLEDGE_SUBMIT)
                        .requestMatchers("/me/notifications/**", "/me/im/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/admin/invite").hasAnyAuthority(RolePermissions.SUPER_ADMIN, RolePermissions.COMPANY_ADMIN, RolePermissions.EMPLOYEE)
                        .requestMatchers("/admin/materials/**").hasAuthority(Permission.MATERIAL_MANAGE)
                        .requestMatchers("/admin/skills/picker").hasAuthority(Permission.MATERIAL_MANAGE)
                        .requestMatchers("/admin/users/picker").hasAuthority(Permission.MATERIAL_MANAGE)
                        // 账号停用/重置密码属于用户管理，不能只靠 DASHBOARD_VIEW
                        .requestMatchers(HttpMethod.PUT, "/admin/users/*/status").hasAuthority(Permission.USER_MANAGE)
                        .requestMatchers(HttpMethod.POST, "/admin/users/*/reset-password").hasAuthority(Permission.USER_MANAGE)
                        .requestMatchers("/admin/skills/*/materials/**").hasAuthority(Permission.MATERIAL_MANAGE)
                        .requestMatchers("/admin/skills/**").hasAuthority(Permission.SKILL_MANAGE)
                        .requestMatchers("/admin/conversations/**").hasAuthority(Permission.CONVERSATION_VIEW)
                        .requestMatchers("/admin/experts/**").hasAuthority(Permission.EXPERT_MANAGE)
                        .requestMatchers("/admin/**").hasAuthority(Permission.DASHBOARD_VIEW)

                        // ── 访谈：非游客（权限码正面表达） ──
                        .requestMatchers("/interviews/**").hasAuthority(Permission.INTERVIEW_USE)

                        // ── 颗粒操作 ──
                        .requestMatchers("/grains/**").authenticated()

                        // ── 分身状态变更：B端管理员 + C端注册用户（控制器内做属主校验） ──
                        .requestMatchers(HttpMethod.PUT, "/skills/*/status").hasAnyAuthority(Permission.SKILL_MANAGE, "c_user")

                        // ── 分身列表 ──
                        .requestMatchers(HttpMethod.GET, "/skills/list").hasAuthority(Permission.SKILL_USE)

                        // ── 企业总调度问答：仅 B 端 ──
                        .requestMatchers(HttpMethod.POST, "/skills/enterprise/chat").hasAnyAuthority(RolePermissions.SUPER_ADMIN, RolePermissions.COMPANY_ADMIN, RolePermissions.EMPLOYEE)

                        // ── 聊天全家桶 ──
                        .requestMatchers("/skills/**").hasAuthority(Permission.SKILL_USE)

                        // ── 报告 ──
                        .requestMatchers("/reports/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/reports/by-session/**").authenticated()

                        // ── B 端内部端点（原靠 anyRequest 兜底，现显式声明）──
                        .requestMatchers("/spaces/**").hasAnyAuthority(RolePermissions.SUPER_ADMIN, RolePermissions.COMPANY_ADMIN, RolePermissions.EMPLOYEE)
                        .requestMatchers("/tools/**").hasAnyAuthority(RolePermissions.SUPER_ADMIN, RolePermissions.COMPANY_ADMIN, RolePermissions.EMPLOYEE)
                        .requestMatchers("/workbench/**").hasAnyAuthority(RolePermissions.SUPER_ADMIN, RolePermissions.COMPANY_ADMIN, RolePermissions.EMPLOYEE)
                        .requestMatchers("/im/channels/**").hasAuthority(Permission.IM_MANAGE)
                        .requestMatchers(HttpMethod.GET, "/api/domains").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/skills/*/domain-config").hasAuthority(Permission.SKILL_USE)
                        .requestMatchers(HttpMethod.GET, "/experts/available").hasAuthority(Permission.SKILL_USE)

                        // ── 默认拒绝：未显式声明的接口一律 403 ──
                        .anyRequest().denyAll())

                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
