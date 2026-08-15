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
                        .requestMatchers(HttpMethod.POST, "/c/auth/register").hasAuthority("c_guest")
                        .requestMatchers(HttpMethod.POST, "/c/auth/register/new").permitAll()
                        .requestMatchers(HttpMethod.GET, "/c/auth/me").authenticated()

                        // ── Swagger / 健康检查 ──
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()

                        // ── 语音识别（录音后一次性识别，任意有效 JWT）──
                        .requestMatchers(HttpMethod.POST, "/stt/recognize").authenticated()

                        // ── IM 回调：无需 JWT ──
                        .requestMatchers("/im/*/callback").permitAll()

                        // ── 登录状态检查 ──
                        .requestMatchers(HttpMethod.POST, "/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/auth/me").authenticated()

                        // ── 对内分享 ──
                        .requestMatchers(HttpMethod.GET, "/i/*/info").permitAll()
                        .requestMatchers("/i/**").authenticated()

                        // ═══════════════════════════════════════════════════════
                        // 管理后台 — Platform 级（仅 super_admin 拥有对应权限码）
                        // 具体路径在前，通用 /admin/** 在后
                        // ═══════════════════════════════════════════════════════
                        .requestMatchers("/admin/companies/**").hasAuthority(Permission.COMPANY_MANAGE)
                        .requestMatchers("/admin/partners/**").hasAuthority(Permission.PARTNER_MANAGE)
                        .requestMatchers("/admin/im/**").hasAuthority(Permission.IM_MANAGE)
                        .requestMatchers(HttpMethod.POST, "/admin/invite").hasAnyAuthority("super_admin", "company_admin", "employee")
                        .requestMatchers("/admin/materials/**").hasAuthority(Permission.MATERIAL_MANAGE)
                        .requestMatchers("/admin/skills/picker").hasAuthority(Permission.MATERIAL_MANAGE)
                        .requestMatchers("/admin/users/picker").hasAuthority(Permission.MATERIAL_MANAGE)
                        .requestMatchers("/admin/skills/*/materials/**").hasAuthority(Permission.MATERIAL_MANAGE)
                        .requestMatchers("/admin/**").hasAuthority(Permission.DASHBOARD_VIEW)

                        // ── 访谈：B端全员 + C端注册用户 + 合作方（排除游客） ──
                        .requestMatchers("/interviews/**").hasAnyAuthority("super_admin", "company_admin", "employee", "c_user", "c_partner")

                        // ── 颗粒操作 ──
                        .requestMatchers("/grains/**").authenticated()

                        // ── 分身状态变更：B端管理员 + C端注册用户（控制器内做属主校验） ──
                        .requestMatchers(HttpMethod.PUT, "/skills/*/status").hasAnyAuthority(Permission.SKILL_MANAGE, "c_user")

                        // ── 分身列表 ──
                        .requestMatchers(HttpMethod.GET, "/skills/list").hasAuthority(Permission.SKILL_USE)

                        // ── 企业总调度问答：仅 B 端 ──
                        .requestMatchers(HttpMethod.POST, "/skills/enterprise/chat").hasAnyAuthority("super_admin", "company_admin", "employee")

                        // ── 聊天全家桶 ──
                        .requestMatchers("/skills/**").hasAuthority(Permission.SKILL_USE)

                        // ── 报告 ──
                        .requestMatchers("/reports/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/reports/by-session/**").authenticated()

                        // ── 其余内部接口：仅 B 端全员 ──
                        // ⚠️ 注意：C 端接口（c_guest/c_user/c_partner）必须在上方显式声明，
                        // 否则会被此 catch-all 规则拒绝。新增 C 端接口时务必加 .requestMatchers()。
                        .anyRequest().hasAnyAuthority("super_admin", "company_admin", "employee"))

                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
