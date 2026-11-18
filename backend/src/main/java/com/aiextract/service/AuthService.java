package com.aiextract.service;

import com.aiextract.config.RolePermissions;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.LoginRequest;
import com.aiextract.dto.LoginResponse;
import com.aiextract.dto.RegisterRequest;
import com.aiextract.dto.UserInfoResponse;
import com.aiextract.common.ErrorMessages;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.Company;
import com.aiextract.model.CompanyRegisterCode;
import com.aiextract.model.User;
import com.aiextract.repository.CompanyRegisterCodeRepository;
import com.aiextract.repository.CompanyRepository;
import com.aiextract.repository.UserRepository;
import com.aiextract.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;

/**
 * 认证服务
 *
 * <p>处理用户登录、注册和身份验证相关业务逻辑。
 * 密码使用BCrypt加密存储，登录成功后签发24小时有效的JWT Token。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final CompanyRegisterCodeRepository registerCodeRepository;
    private final JwtUtil jwtUtil;
    private final com.aiextract.service.session.SessionService sessionService;
    private final PasswordEncoder passwordEncoder;
    private final com.aiextract.repository.SpaceRepository spaceRepository;
    private final OssService ossService;
    private final UserSecurityService userSecurityService;

    /**
     * 用户登录
     *
     * <p>验证账号密码，检查账户状态，签发JWT Token。</p>
     *
     * @param request 登录请求（companyId、account、password）
     * @return Token及用户信息
     * @throws BusinessException 如果用户不存在（404）、密码错误（401）或账户已禁用（403）
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginResponse login(LoginRequest request) {
        UUID companyId;
        if (request.getInviteToken() != null && !request.getInviteToken().isBlank()) {
            try {
                companyId = jwtUtil.parseInviteToken(request.getInviteToken());
            } catch (Exception e) {
                throw new BusinessException(400, "邀请令牌无效或已过期，请刷新页面重试");
            }
        } else if (request.getCompanyCode() != null && !request.getCompanyCode().isBlank()) {
            // 通过注册码解析企业
            CompanyRegisterCode c = registerCodeRepository.findByCode(
                request.getCompanyCode().trim().toUpperCase())
                .orElseThrow(() -> new BusinessException(400, "注册码无效"));
            if (Boolean.FALSE.equals(c.getEnabled())) {
                throw new BusinessException(400, "注册码已失效");
            }
            if (c.getExpiresAt() != null && c.getExpiresAt().isBefore(LocalDateTime.now())) {
                throw new BusinessException(400, "注册码已过期");
            }
            companyId = c.getCompanyId();
        } else if (request.getCompanyId() != null && !request.getCompanyId().isBlank()) {
            // 内部/QA 跨租户登录：直接指定企业 ID；登录页仍强制企业注册码
            companyId = UUID.fromString(request.getCompanyId());
        } else {
            throw new BusinessException(400, "请提供企业注册码");
        }

        // 查询用户
        // 账号不存在与密码错误统一返回 401，避免按状态码枚举账号
        User user = userRepository.findByCompanyIdAndAccount(companyId, request.getAccount().trim())
                .orElseThrow(() -> {
                    log.warn("登录失败（账号不存在）, companyId: {}, account: {}", companyId, request.getAccount());
                    return new BusinessException(HttpStatus.UNAUTHORIZED.value(), "账号或密码错误");
                });

        // 验证密码
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("登录失败（密码错误）, userId: {}", user.getId());
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "账号或密码错误");
        }

        // 检查账户是否激活
        if (Boolean.FALSE.equals(user.getIsActive())) {
            log.warn("账户已禁用, userId: {}", user.getId());
            throw new BusinessException(HttpStatus.LOCKED.value(), ErrorMessages.ACCOUNT_DISABLED);
        }

        // 企业已归档/停用时不允许再登录：会话签发前拦截，避免发出一个立刻该失效的凭证
        Company company = user.getCompanyId() == null ? null
                : companyRepository.findById(user.getCompanyId()).orElse(null);
        if (company != null && company.getStatus() != null
                && !"active".equals(company.getStatus())) {
            log.warn("企业已停用，拒绝登录, companyId: {}, userId: {}", company.getId(), user.getId());
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "企业已停用，请联系管理员");
        }
        String companyName = company != null ? company.getName() : null;

        // 生成不透明会话（DB 真相源 + Redis 主读；改权限后 epoch 递增即失效）
        String token = sessionService.create(user, null, null).token();

        // 构建响应
        UserInfoResponse userInfo = buildUserInfoResponse(user, companyName);
        LoginResponse response = LoginResponse.builder()
                .token(token)
                .user(userInfo)
                .build();

        log.info("用户登录成功, userId: {}, role: {}", user.getId(), user.getRole());
        return response;
    }

    /**
     * 用户注册
     *
     * <p>校验账号在企业内唯一，BCrypt加密密码后插入用户记录，签发JWT Token。</p>
     *
     * @param request 注册请求（companyId、name、account、password、role）
     * @return Token及用户信息
     * @throws BusinessException 如果账号在企业内已存在（400）
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginResponse register(RegisterRequest request) {
        validatePasswordStrength(request.getPassword());
        UUID companyId = UUID.fromString(request.getCompanyId());
        String account = request.getAccount() == null ? "" : request.getAccount().trim();
        if (account.isEmpty()) {
            throw new BusinessException(400, "账号不能为空");
        }

        // 账号是全局唯一索引：先查全局，避免跨企业同名账号在 commit 时撞约束变成 500
        if (userRepository.existsByAccount(account)
                || userRepository.existsByCompanyIdAndAccount(companyId, account)) {
            log.warn("账号已存在, companyId: {}, account: {}", companyId, account);
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), ErrorMessages.ACCOUNT_EXISTS);
        }

        // 查询企业
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> {
                    log.warn("企业不存在, companyId: {}", companyId);
                    return new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.COMPANY_NOT_FOUND);
                });

        // 构建用户实体
        LocalDateTime now = LocalDateTime.now();
        User user = User.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .name(request.getName())
                .account(account)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(request.getRole())
                .source(User.SOURCE_ENTERPRISE)
                .isActive(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        try {
            // 显式 flush：并发双提交撞全局唯一约束时在事务内转成 400，而不是提交阶段 500
            userRepository.saveAndFlush(user);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), ErrorMessages.ACCOUNT_EXISTS);
        }

        // 所有 B 端角色注册时自动创建个人空间（H5 邀请访谈需要）
        com.aiextract.model.Space space = com.aiextract.model.Space.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .title(user.getName() + "的空间")
                .isPublic(true)
                .status("active")
                .createdAt(now)
                .updatedAt(now)
                .build();
        spaceRepository.save(space);
        log.info("注册自动创建空间, userId: {}, role: {}, spaceId: {}", user.getId(), request.getRole(), space.getId());

        // 生成不透明会话（DB 真相源 + Redis 主读；改权限后 epoch 递增即失效）
        String token = sessionService.create(user, null, null).token();

        // 构建响应
        UserInfoResponse userInfo = buildUserInfoResponse(user, company.getName());
        LoginResponse response = LoginResponse.builder()
                .token(token)
                .user(userInfo)
                .build();

        log.info("用户注册成功, userId: {}, account: {}", user.getId(), user.getAccount());
        return response;
    }

    /**
     * 使用企业注册码注册（公开接口）。
     *
     * <p>角色只认注册码的 {@code defaultRole}，请求方无法指定，防止员工码提权。
     * 整个方法在一个事务内：先创建用户，再原子消费注册码；消费失败（并发超发/刚被停用）
     * 会回滚用户创建，不会出现「注册成功但码未扣」。</p>
     *
     * @param companyCode 企业注册码
     * @param account     登录账号
     * @param password    初始密码
     * @param name        姓名
     * @return Token 及用户信息
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginResponse registerWithCode(String companyCode, String account, String password, String name) {
        validatePasswordStrength(password);
        CompanyRegisterCode code = registerCodeRepository.findByCode(companyCode)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST.value(), "注册码无效"));

        LocalDateTime now = LocalDateTime.now();
        if (Boolean.FALSE.equals(code.getEnabled())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "注册码已停用");
        }
        if (code.getExpiresAt() != null && code.getExpiresAt().isBefore(now)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "注册码已过期，请联系管理员重新生成");
        }
        if (code.getMaxUses() != null && code.getMaxUses() > 0 && code.getUsedCount() >= code.getMaxUses()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "注册码已达使用上限");
        }

        // 角色以注册码为准，请求体不能覆盖；不在可注册角色内时降级为员工
        String role = code.getDefaultRole() != null && RolePermissions.REGISTRABLE_ROLES.contains(code.getDefaultRole())
                ? code.getDefaultRole() : RolePermissions.EMPLOYEE;

        RegisterRequest request = RegisterRequest.builder()
                .companyId(code.getCompanyId().toString())
                .account(account).password(password).name(name)
                .role(role).build();
        LoginResponse response = register(request);

        UUID userId = UUID.fromString(response.getUser().getId());
        int rows = registerCodeRepository.consume(code.getId(), userId, now);
        if (rows == 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "注册码已失效，请重新获取");
        }
        log.info("注册码注册成功, codeId: {}, userId: {}, role: {}", code.getId(), userId, role);
        return response;
    }

    /**
     * 获取当前登录用户信息
     *
     * <p>从JWT中提取userId，查询用户及关联企业信息。</p>
     *
     * @param userId 当前登录用户的唯一标识
     * @return 用户详细信息
     * @throws BusinessException 如果用户不存在（404）
     */
    @Transactional(readOnly = true)
    public UserInfoResponse getCurrentUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("用户不存在, userId: {}", userId);
                    return new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.USER_NOT_FOUND);
                });

        // 查询企业名称
        Company company = companyRepository.findById(user.getCompanyId())
                .orElse(null);
        String companyName = company != null ? company.getName() : null;

        log.debug("获取当前用户信息, userId: {}", userId);
        return buildUserInfoResponse(user, companyName);
    }

    /**
     * B端用户上传个人头像到 OSS。
     *
     * @param userId 当前登录用户 ID
     * @param file   头像文件
     * @return OSS 预签名 URL
     */
    @Transactional(rollbackFor = Exception.class)
    public String uploadAvatar(UUID userId, MultipartFile file) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.USER_NOT_FOUND));

        String extension = com.aiextract.common.UploadValidator.requireImage(file, 2 * 1024 * 1024);
        String safeName = System.currentTimeMillis() + extension;
        String objectKey = "avatars/users/" + userId + "/" + safeName;
        String avatarUrl = ossService.upload(objectKey, file);

        user.setAvatarUrl(avatarUrl);
        userRepository.save(user);

        log.info("B端用户头像已更新 userId={}", userId);
        return avatarUrl;
    }

    /**
     * 构建用户信息响应对象
     *
     * @param user        用户实体
     * @param companyName 企业名称
     * @return UserInfoResponse
     */
    private UserInfoResponse buildUserInfoResponse(User user, String companyName) {
        // 注意：此方法被 login/register 复用，彼时 SecurityContext 尚未建立，
        // 故从 role 映射表派生（与 JwtUtil 签发时的 permissions claim 同源）。
        return UserInfoResponse.builder()
                .id(user.getId().toString())
                .name(user.getName())
                .role(user.getRole())
                .avatarUrl(user.getAvatarUrl())
                .companyId(user.getCompanyId() != null ? user.getCompanyId().toString() : null)
                .companyName(companyName)
                .permissions(new ArrayList<>(RolePermissions.getPermissions(user.getRole())))
                .mustChangePassword(Boolean.TRUE.equals(user.getMustChangePassword()))
                .build();
    }

    // ==================== 密码生命周期 / 账号停用 ====================

    /** 注册密码强度：统一走 PasswordPolicy，与 C 端注册同源 */
    private void validatePasswordStrength(String password) {
        com.aiextract.common.PasswordPolicy.validate(password);
    }

    /**
     * 修改自己的密码：校验旧密码，更新后提升 tokenVersion（其他会话立即失效），返回新 token。
     */
    @Transactional(rollbackFor = Exception.class)
    public String changePassword(UUID userId, String oldPassword, String newPassword) {
        validatePasswordStrength(newPassword);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.USER_NOT_FOUND));
        if (oldPassword == null || !passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BusinessException(400, "原密码不正确");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException(400, "新密码不能与原密码相同");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        // 走统一入口：自增安全版本号并在事务提交后刷新 Redis 缓存，
        // 否则缓存里仍是旧版本，新签发的 token 会被判过期
        userSecurityService.onAuthoritiesChanged(user);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("用户修改密码 userId={}", userId);
        // 旧会话已由 onAuthoritiesChanged 通过 epoch 全部失效；为当前设备签发新会话
        return sessionService.create(user, null, null).token();
    }

    /** 管理端停用/启用账号：不允许操作自己、超管，以及其他企业的用户 */
    @Transactional(rollbackFor = Exception.class)
    public void setUserActive(UUID targetUserId, boolean active) {
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.USER_NOT_FOUND));
        UUID operatorCompany = TokenContext.getCompanyId();
        boolean superAdmin = com.aiextract.config.CompanyScopeService.isSuperAdminStatic();
        if (!superAdmin && !java.util.Objects.equals(operatorCompany, target.getCompanyId())) {
            throw new BusinessException(404, ErrorMessages.USER_NOT_FOUND);
        }
        if (RolePermissions.SUPER_ADMIN.equals(target.getRole())) {
            throw new BusinessException(403, "不能停用平台超级管理员");
        }
        if (java.util.Objects.equals(TokenContext.get(), targetUserId)) {
            throw new BusinessException(400, "不能停用自己的账号");
        }
        target.setIsActive(active);
        if (!active) {
            // 停用即让该用户所有已签发 token 失效（同时刷新版本缓存）
            userSecurityService.onAuthoritiesChanged(target);
        }
        target.setUpdatedAt(LocalDateTime.now());
        userRepository.save(target);
        log.info("用户状态变更 userId={} active={}", targetUserId, active);
    }

    /** 管理端重置密码：生成一次性密码，要求下次登录修改，并让旧 token 失效 */
    @Transactional(rollbackFor = Exception.class)
    public String resetPassword(UUID targetUserId) {
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.USER_NOT_FOUND));
        UUID operatorCompany = TokenContext.getCompanyId();
        boolean superAdmin = com.aiextract.config.CompanyScopeService.isSuperAdminStatic();
        if (!superAdmin && !java.util.Objects.equals(operatorCompany, target.getCompanyId())) {
            throw new BusinessException(404, ErrorMessages.USER_NOT_FOUND);
        }
        if (RolePermissions.SUPER_ADMIN.equals(target.getRole())
                && !java.util.Objects.equals(TokenContext.get(), targetUserId)) {
            throw new BusinessException(403, "不能重置平台超级管理员的密码");
        }
        String temporary = randomPassword(10);
        target.setPasswordHash(passwordEncoder.encode(temporary));
        target.setMustChangePassword(true);
        userSecurityService.onAuthoritiesChanged(target);
        target.setUpdatedAt(LocalDateTime.now());
        userRepository.save(target);
        log.info("管理员重置密码 userId={}", targetUserId);
        return temporary;
    }

    private static String randomPassword(int length) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
