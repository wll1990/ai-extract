package com.aiextract.service;

import com.aiextract.dto.LoginRequest;
import com.aiextract.dto.LoginResponse;
import com.aiextract.dto.RegisterRequest;
import com.aiextract.dto.UserInfoResponse;
import com.aiextract.common.ErrorMessages;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.Company;
import com.aiextract.model.User;
import com.aiextract.repository.CompanyRepository;
import com.aiextract.repository.UserRepository;
import com.aiextract.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

    private static final String ROLE_EMPLOYEE = "employee";

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final com.aiextract.repository.SpaceRepository spaceRepository;

    /**
     * 用户登录
     *
     * <p>验证账号密码，检查账户状态，签发JWT Token。</p>
     *
     * @param request 登录请求（companyId、account、password）
     * @return Token及用户信息
     * @throws BusinessException 如果用户不存在（404）、密码错误（401）或账户已禁用（403）
     */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        UUID companyId = UUID.fromString(request.getCompanyId());

        // 查询用户
        User user = userRepository.findByCompanyIdAndAccount(companyId, request.getAccount())
                .orElseThrow(() -> {
                    log.warn("用户不存在, companyId: {}, account: {}", companyId, request.getAccount());
                    return new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.USER_NOT_FOUND);
                });

        // 验证密码
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("密码错误, userId: {}", user.getId());
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), ErrorMessages.PASSWORD_WRONG);
        }

        // 检查账户是否激活
        if (Boolean.FALSE.equals(user.getIsActive())) {
            log.warn("账户已禁用, userId: {}", user.getId());
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), ErrorMessages.ACCOUNT_DISABLED);
        }

        // 生成Token
        String token = jwtUtil.generateToken(user.getId(), user.getCompanyId(), user.getRole());

        // 查询企业名称
        Company company = companyRepository.findById(user.getCompanyId())
                .orElse(null);
        String companyName = company != null ? company.getName() : null;

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
        UUID companyId = UUID.fromString(request.getCompanyId());

        // 校验账号在企业内唯一
        if (userRepository.existsByCompanyIdAndAccount(companyId, request.getAccount())) {
            log.warn("账号已存在, companyId: {}, account: {}", companyId, request.getAccount());
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
                .account(request.getAccount())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(request.getRole())
                .isActive(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        userRepository.save(user);

        // 仅员工角色自动创建个人空间，管理员不需要空间
        if (ROLE_EMPLOYEE.equals(request.getRole())) {
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
            log.info("自动创建员工空间, userId: {}, spaceId: {}", user.getId(), space.getId());
        }

        // 生成Token
        String token = jwtUtil.generateToken(user.getId(), user.getCompanyId(), user.getRole());

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
     * 构建用户信息响应对象
     *
     * @param user        用户实体
     * @param companyName 企业名称
     * @return UserInfoResponse
     */
    private UserInfoResponse buildUserInfoResponse(User user, String companyName) {
        return UserInfoResponse.builder()
                .id(user.getId().toString())
                .name(user.getName())
                .role(user.getRole())
                .avatarUrl(user.getAvatarUrl())
                .companyId(user.getCompanyId().toString())
                .companyName(companyName)
                .build();
    }
}
