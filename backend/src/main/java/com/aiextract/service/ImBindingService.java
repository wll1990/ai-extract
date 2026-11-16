package com.aiextract.service;

import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.ImBindCode;
import com.aiextract.model.ImChannel;
import com.aiextract.model.ImUserBinding;
import com.aiextract.repository.ImBindCodeRepository;
import com.aiextract.repository.ImUserBindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IM 账号绑定 —— 平台发码，用户在 IM 里发「绑定 123456」完成。
 *
 * <p>不依赖企业通讯录权限：只把「当前会话里的平台用户」与「当前登录的 B 端用户」建立映射。
 * 推送只发事件摘要与站内链接，不发正文/客户数据。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImBindingService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern BIND_COMMAND =
            Pattern.compile("^(?:绑定|bind)\\s*[:：]?\\s*(\\d{6})\\s*$", Pattern.CASE_INSENSITIVE);
    private static final int CODE_TTL_MINUTES = 10;

    private final ImBindCodeRepository codeRepository;
    private final ImUserBindingRepository bindingRepository;

    public record BindCode(String code, LocalDateTime expiresAt) {
    }

    public record BindingView(UUID id, String channelType, String botId, String displayName,
                              String chatId, LocalDateTime createdAt, LocalDateTime lastSeenAt) {
    }

    /** 当前登录用户生成绑定码（旧码不撤销，10 分钟自然过期）。 */
    @Transactional(rollbackFor = Exception.class)
    public BindCode createCode() {
        UUID userId = TokenContext.get();
        UUID companyId = TokenContext.getCompanyId();
        if (userId == null || companyId == null) {
            throw new BusinessException(403, "缺少用户或企业信息");
        }
        codeRepository.deleteExpired(LocalDateTime.now());
        String code = null;
        for (int i = 0; i < 20; i++) {
            String candidate = String.format("%06d", RANDOM.nextInt(1_000_000));
            if (!codeRepository.existsById(candidate)) {
                code = candidate;
                break;
            }
        }
        if (code == null) {
            throw new BusinessException(500, "生成绑定码失败，请重试");
        }
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(CODE_TTL_MINUTES);
        codeRepository.save(ImBindCode.builder()
                .code(code)
                .companyId(companyId)
                .userId(userId)
                .expiresAt(expiresAt)
                .createdAt(LocalDateTime.now())
                .build());
        return new BindCode(code, expiresAt);
    }

    /**
     * 处理 IM 入站消息里的绑定命令。
     *
     * @return 需要回复给用户的文本；不是绑定命令时返回 empty，交回正常对话链路
     */
    @Transactional(rollbackFor = Exception.class)
    public Optional<String> handleBindCommand(ImChannel channel, String text,
                                              String externalUserId, String chatId,
                                              String displayName) {
        if (text == null || externalUserId == null) {
            return Optional.empty();
        }
        Matcher matcher = BIND_COMMAND.matcher(text.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String code = matcher.group(1);
        ImBindCode row = codeRepository.findById(code).orElse(null);
        if (row == null) {
            return Optional.of("绑定码不存在或已使用，请在平台重新生成后再试。");
        }
        if (row.getExpiresAt().isBefore(LocalDateTime.now())) {
            codeRepository.deleteById(code);
            return Optional.of("绑定码已过期，请在平台重新生成。");
        }
        if (!row.getCompanyId().equals(channel.getCompanyId())) {
            return Optional.of("该绑定码不属于当前企业，请确认机器人与账号来自同一企业。");
        }

        // 同一 IM 身份之前可能绑过别人：改绑到当前用户
        bindingRepository.findByChannelTypeAndExternalUserId(channel.getChannelType(), externalUserId)
                .ifPresent(existing -> {
                    existing.setUserId(row.getUserId());
                    existing.setCompanyId(row.getCompanyId());
                    existing.setBotId(channel.getBotId());
                    existing.setChatId(chatId);
                    existing.setDisplayName(displayName);
                    existing.setLastSeenAt(LocalDateTime.now());
                    bindingRepository.save(existing);
                });
        if (bindingRepository.findByChannelTypeAndExternalUserId(channel.getChannelType(),
                externalUserId).isEmpty()) {
            bindingRepository.save(ImUserBinding.builder()
                    .id(UUID.randomUUID())
                    .companyId(row.getCompanyId())
                    .channelType(channel.getChannelType())
                    .botId(channel.getBotId())
                    .userId(row.getUserId())
                    .externalUserId(externalUserId)
                    .chatId(chatId)
                    .displayName(displayName)
                    .createdAt(LocalDateTime.now())
                    .lastSeenAt(LocalDateTime.now())
                    .build());
        }
        // 同一用户在同一渠道只保留最新一条，避免推送重复
        bindingRepository.findByCompanyIdAndChannelTypeAndUserId(
                        row.getCompanyId(), channel.getChannelType(), row.getUserId())
                .filter(binding -> !binding.getExternalUserId().equals(externalUserId))
                .ifPresent(bindingRepository::delete);
        codeRepository.deleteById(code);
        log.info("IM 绑定成功 company={} channel={} user={} external={}",
                row.getCompanyId(), channel.getChannelType(), row.getUserId(), externalUserId);
        return Optional.of("绑定成功。之后培训提醒会通过这里发给你；如需解绑，在平台「我的培训」里操作。");
    }

    /** 记录已绑定用户的最后一次会话（chatId 可能变化时同步） */
    @Transactional(rollbackFor = Exception.class)
    public void touch(ImChannel channel, String externalUserId, String chatId) {
        bindingRepository.findByChannelTypeAndExternalUserId(channel.getChannelType(), externalUserId)
                .ifPresent(b -> bindingRepository.touch(b.getId(), chatId, LocalDateTime.now()));
    }

    public List<BindingView> listMine() {
        UUID userId = TokenContext.get();
        return bindingRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(b -> new BindingView(b.getId(), b.getChannelType(), b.getBotId(),
                        b.getDisplayName(), b.getChatId(), b.getCreatedAt(), b.getLastSeenAt()))
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void unbind(UUID id) {
        UUID userId = TokenContext.get();
        ImUserBinding binding = bindingRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(404, "绑定不存在"));
        bindingRepository.delete(binding);
    }
}
