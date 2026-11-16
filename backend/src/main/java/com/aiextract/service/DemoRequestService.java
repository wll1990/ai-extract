package com.aiextract.service;

import com.aiextract.dto.DemoRequestCreate;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.DemoRequest;
import com.aiextract.model.User;
import com.aiextract.model.UserNotification;
import com.aiextract.repository.DemoRequestRepository;
import com.aiextract.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/** 门户留资：公开提交 + 运营查询。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoRequestService {

    private final DemoRequestRepository repository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    @Transactional
    public UUID create(DemoRequestCreate req) {
        if (req.getWebsite() != null && !req.getWebsite().isBlank()) {
            throw new BusinessException(400, "提交无效");
        }
        String contact = req.getContact().trim();

        // 同一联系方式 10 分钟内重复提交视为同一次，避免刷表
        var recent = repository.findFirstByContactAndCreatedAtAfterOrderByCreatedAtDesc(
                contact, LocalDateTime.now().minusMinutes(10));
        if (recent.isPresent()) {
            return recent.get().getId();
        }

        DemoRequest entity = DemoRequest.builder()
                .id(UUID.randomUUID())
                .companyName(req.getCompanyName().trim())
                .contactName(req.getContactName().trim())
                .role(trimToNull(req.getRole()))
                .teamSize(trimToNull(req.getTeamSize()))
                .contact(contact)
                .message(trimToNull(req.getMessage()))
                .source("portal")
                .status("new")
                .createdAt(LocalDateTime.now())
                .build();
        repository.save(entity);
        notifyOperators(entity);
        return entity.getId();
    }

    /** 运营更新跟进状态。实体由 JPA 管理，脏检查直接落库。 */
    @Transactional
    public void updateStatus(UUID id, String status) {
        DemoRequest entity = repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "预约记录不存在"));
        entity.setStatus(status);
    }

    @Transactional(readOnly = true)
    public Page<DemoRequest> list(int page, int size, String status) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        PageRequest pageable = PageRequest.of(safePage - 1, safeSize);
        if (status == null || status.isBlank()) {
            return repository.findAllByOrderByCreatedAtDesc(pageable);
        }
        return repository.findByStatusOrderByCreatedAtDesc(status, pageable);
    }

    private void notifyOperators(DemoRequest r) {
        String content = r.getCompanyName() + " · " + r.getContactName() + "（" + r.getContact() + "）预约演示";
        for (User admin : userRepository.findByRoleAndIsActiveTrue("super_admin")) {
            if (admin.getCompanyId() == null) {
                continue;
            }
            notificationService.notifyNow(admin.getId(), admin.getCompanyId(),
                    UserNotification.TYPE_DEMO_REQUEST, "新的预约演示",
                    content, "/admin/demo-requests", "demo_request", r.getId(),
                    "demo_request:" + r.getId());
        }
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
