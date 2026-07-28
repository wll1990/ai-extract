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
 * 使用BCrypt作为密码加密算法。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    /**
     * 配置安全过滤器链
     *
     * @param http HttpSecurity配置对象
     * @return SecurityFilterChain
     * @throws Exception 配置异常
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CORS（使用 CorsConfig 中定义的 CorsConfigurationSource bean）
                .cors(cors -> {})
                // 禁用CSRF（API服务不使用Cookie）
                .csrf(AbstractHttpConfigurer::disable)

                // 无状态会话（JWT认证）
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 接口权限配置
                // 说明：B 端角色 = SUPER_ADMIN / EMPLOYEE（企业 user 表），
                //      C 端角色 = C_GUEST / C_USER（app_user 表，仅分享页链路使用）。
                //      规则顺序敏感：前面的规则优先命中。
                .authorizeHttpRequests(auth -> auth
                        // 异步分发（SSE等）：初始请求已认证，跳过二次校验
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        // B 端认证接口：无需认证
                        .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/register").permitAll()
                        // 对外公开接口（分享落地信息、游客发证、企业注册码信息、邀请码信息）：无需认证
                        .requestMatchers("/public/**").permitAll()
                        // C 端认证接口：登录公开；注册=游客升级（需游客身份）；独立注册公开；me 需任意认证
                        .requestMatchers(HttpMethod.POST, "/c/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/c/auth/register").hasRole("C_GUEST")
                        .requestMatchers(HttpMethod.POST, "/c/auth/register/new").permitAll()
                        .requestMatchers(HttpMethod.GET, "/c/auth/me").authenticated()
                        // Swagger文档（如后续集成）
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // IM回调接口（无需JWT，使用IM平台自有签名验证）
                        .requestMatchers("/im/*/callback").permitAll()
                        // 健康检查
                        .requestMatchers("/actuator/health").permitAll()
                        // 获取当前用户信息：需要认证
                        .requestMatchers(HttpMethod.GET, "/auth/me").authenticated()
                        // 对内分享落地页信息：无需认证（登录页需要公司名，分享码本身就是密钥）
                        .requestMatchers(HttpMethod.GET, "/i/*/info").permitAll()
                        // 对内分享：需认证（公司校验在业务层做）
                        .requestMatchers("/i/**").authenticated()
                        // 管理接口：仅超级管理员
                        .requestMatchers("/admin/**").hasRole("SUPER_ADMIN")
                        // 访谈：B 端全员 + C 端
                        .requestMatchers("/interviews/**").hasAnyRole("SUPER_ADMIN", "COMPANY_ADMIN", "EMPLOYEE", "C_USER", "C_PARTNER")
                        // C 端颗粒操作接口
                        .requestMatchers("/grains/**").authenticated()
                        // 分身状态变更：B端管理员 + C端属主
                        .requestMatchers(HttpMethod.PUT, "/skills/*/status").hasAnyRole("SUPER_ADMIN", "COMPANY_ADMIN", "C_USER")
                        // 分身列表：B 端全员 + C 端
                        .requestMatchers(HttpMethod.GET, "/skills/list").hasAnyRole("SUPER_ADMIN", "COMPANY_ADMIN", "EMPLOYEE", "C_USER")
                        // 企业总调度问答：B 端全员
                        .requestMatchers(HttpMethod.POST, "/skills/enterprise/chat").hasAnyRole("SUPER_ADMIN", "COMPANY_ADMIN", "EMPLOYEE")
                        // 聊天全家桶（chat/practice/conversations/scene-tags...）：任意认证身份，含 C 端
                        .requestMatchers("/skills/**").authenticated()
                        // 报告详情查看：所有认证用户可读（含 C端 H5 报告页）
                        // 仅单层路径 /reports/{id}，不包含 /reports/{id}/download 等子路径
                        .requestMatchers(HttpMethod.GET, "/reports/*").authenticated()
                        // H5 按 sessionId 查报告 HTML（含就绪检查和轮询）
                        .requestMatchers(HttpMethod.GET, "/reports/by-session/**").authenticated()
                        // 其余内部接口：B 端全员，对 C 端关门
                        .anyRequest().hasAnyRole("SUPER_ADMIN", "COMPANY_ADMIN", "EMPLOYEE"))

                // 添加JWT过滤器（在UsernamePasswordAuthenticationFilter之前）
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 密码编码器
     *
     * <p>使用BCrypt算法进行密码加密和验证。</p>
     *
     * @return BCryptPasswordEncoder实例
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
