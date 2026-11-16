package com.aiextract.service;

import com.aiextract.config.TokenContext;
import com.aiextract.dto.CoachLedgerRow;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.ReviewReport;
import com.aiextract.model.ReviewSession;
import com.aiextract.repository.ReviewReportRepository;
import com.aiextract.repository.ReviewSegmentRepository;
import com.aiextract.repository.ReviewSessionRepository;
import com.aiextract.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 教练台账：谁找我的分身复盘过。默认只给摘要，原文需销售授权。
 */
@Service
@RequiredArgsConstructor
public class SalesCoachLedgerService {

    private final SalesAccessService access;
    private final ReviewSessionRepository sessionRepository;
    private final ReviewReportRepository reportRepository;
    private final ReviewSegmentRepository segmentRepository;
    private final UserRepository userRepository;

    public Page<CoachLedgerRow> ledger(int page, int size) {
        UUID me = TokenContext.get();
        UUID companyId = access.requireCompanyId();
        Pageable p = PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), 100));
        Page<ReviewSession> rows = sessionRepository
                .findByCompanyIdAndCoachOwnerIdOrderByCreatedAtDesc(companyId, me, p);
        Map<UUID, String> names = new HashMap<>();
        userRepository.findAllById(rows.getContent().stream()
                        .map(ReviewSession::getReviewerId).distinct().toList())
                .forEach(u -> names.put(u.getId(), u.getName()));
        List<UUID> sessionIds = rows.getContent().stream().map(ReviewSession::getId).toList();
        Map<UUID, ReviewReport> latestReports = sessionIds.isEmpty()
                ? Map.of()
                : reportRepository.findBySessionIdInOrderBySessionIdAscVersionDesc(sessionIds)
                        .stream().collect(java.util.stream.Collectors.toMap(
                                ReviewReport::getSessionId, r -> r, (a, b) -> a));
        return rows.map(s -> {
            ReviewReport latest = latestReports.get(s.getId());
            return new CoachLedgerRow(s.getId(), names.get(s.getReviewerId()),
                    s.getCustomerName(), s.getTopic(),
                    latest == null ? null : latest.getSummary(),
                    latest == null ? null : latest.getStatus(),
                    latest == null ? null : latest.getErrorMessage(),
                    s.getStatus(), s.isShareTranscriptWithCoach(), s.getCreatedAt());
        });
    }

    public Map<String, Object> transcript(UUID sessionId) {
        UUID me = TokenContext.get();
        ReviewSession s = sessionRepository.findById(sessionId)
                .filter(x -> x.getCompanyId().equals(access.requireCompanyId()))
                .filter(x -> x.getCoachOwnerId().equals(me))
                .filter(ReviewSession::isShareTranscriptWithCoach)
                .orElseThrow(() -> new BusinessException(404, "复盘不存在或未授权查看原文"));
        List<Map<String, Object>> segments = segmentRepository
                .findBySessionIdOrderBySeqAsc(sessionId).stream()
                .map(x -> Map.<String, Object>of(
                        "seq", x.getSeq(), "speaker", x.getSpeaker(), "text", x.getText()))
                .toList();
        return Map.of("segments", segments);
    }
}
