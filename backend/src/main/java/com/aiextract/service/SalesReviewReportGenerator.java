package com.aiextract.service;

import com.aiextract.config.TokenContext;
import com.aiextract.model.ReviewReport;
import com.aiextract.model.ReviewSegment;
import com.aiextract.model.ReviewSession;
import com.aiextract.model.Skill;
import com.aiextract.repository.ReviewCoachNoteRepository;
import com.aiextract.repository.ReviewReportRepository;
import com.aiextract.repository.ReviewSegmentRepository;
import com.aiextract.repository.ReviewSessionRepository;
import com.aiextract.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 报告生成：读数据 → RAG → LLM → 严格解析 → 短事务落库。整段无事务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesReviewReportGenerator {

    private final ReviewReportRepository reportRepository;
    private final ReviewSessionRepository sessionRepository;
    private final ReviewSegmentRepository segmentRepository;
    private final ReviewCoachNoteRepository noteRepository;
    private final SkillRepository skillRepository;
    private final GrainRetriever grainRetriever;
    private final SalesReviewPromptBuilder promptBuilder;
    private final SalesReviewReportParser parser;
    private final SalesReviewReportService reportService;
    private final ChatStreamAdapter chatStreamAdapter;
    private final ChatClientResolver resolver;

    public void generate(UUID reportId, String owner) {
        ReviewReport report = reportRepository.findById(reportId).orElseThrow();
        if (!"generating".equals(report.getStatus()) || !owner.equals(report.getLeaseOwner())) {
            return;
        }
        ReviewSession session = sessionRepository.findById(report.getSessionId()).orElseThrow();
        List<ReviewSegment> segments = segmentRepository.findBySessionIdOrderBySeqAsc(session.getId());

        TokenContext.callWith(report.getCreatedBy(), report.getCompanyId(), () -> {
            List<GrainRetriever.GrainResult> grains = retrieveGrains(session, segments);
            // 真人教练补充必须进入报告，否则「请教练补充」对最终结论没有影响
            String coachNotes = noteRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())
                    .stream().map(n -> "- " + n.getContent())
                    .collect(Collectors.joining("\n"));
            SalesReviewPromptBuilder.Prompt prompt = promptBuilder.buildReport(session, segments, grains, coachNotes);
            String content = chatStreamAdapter.chat(prompt.system(), prompt.user());
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("模型返回为空");
            }
            Set<UUID> segmentIds = segments.stream()
                    .map(ReviewSegment::getId).collect(Collectors.toSet());
            // 必须保持段落顺序：跨段引用校验依赖整篇拼接后的文本
            Map<UUID, String> texts = segments.stream()
                    .collect(Collectors.toMap(ReviewSegment::getId, ReviewSegment::getText,
                            (a, b) -> a, java.util.LinkedHashMap::new));
            SalesReviewReportParser.ParsedReport parsed = parser.parse(content, segmentIds, texts);
            String modelName = resolver.currentModelName();
            reportService.complete(report.getId(), owner, parsed.summary(), parsed.payloadJson(),
                    modelName);
            return null;
        });
    }

    private List<GrainRetriever.GrainResult> retrieveGrains(ReviewSession session,
                                                            List<ReviewSegment> segments) {
        Skill coach = skillRepository.findById(session.getCoachSkillId()).orElse(null);
        if (coach == null || coach.getSpaceId() == null) {
            return List.of();
        }
        return grainRetriever.retrieveHybrid(buildQuery(session, segments), coach.getSpaceId(), 8);
    }

    private String buildQuery(ReviewSession session, List<ReviewSegment> segments) {
        StringBuilder q = new StringBuilder();
        if (session.getCustomerName() != null) {
            q.append("客户：").append(session.getCustomerName()).append(' ');
        }
        if (session.getTopic() != null) {
            q.append("主题：").append(session.getTopic()).append(' ');
        }
        segments.stream().limit(10).forEach(s -> q.append(s.getText()).append('\n'));
        String text = q.toString();
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}
