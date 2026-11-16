package com.aiextract;

import com.aiextract.dto.IndustryCaseRequest;
import com.aiextract.dto.KnowledgeEntryRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库/案例库请求 DTO 校验单元测试 — 纯 Jakarta Validation，无 Spring 上下文。
 *
 * <p>验证必填字段（title/category/sourceType/content/expireAt）的 @NotBlank/@NotNull
 * 在空值/缺失时正确触发校验违规。</p>
 */
public class KnowledgeCaseRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private KnowledgeEntryRequest validKnowledgeRequest() {
        return KnowledgeEntryRequest.builder()
                .title("BANT 四问法")
                .category("methodology")
                .sourceType("public")
                .content("预算、权限、需求、时间线四要素确认。")
                .expireAt(LocalDateTime.now().plusDays(30))
                .build();
    }

    @Test
    void knowledgeRequest_valid_noViolation() {
        Set<ConstraintViolation<KnowledgeEntryRequest>> violations = validator.validate(validKnowledgeRequest());
        assertTrue(violations.isEmpty(), "合法请求不应有校验违规");
    }

    @Test
    void knowledgeRequest_blankTitle_violation() {
        KnowledgeEntryRequest req = validKnowledgeRequest();
        req.setTitle("");
        Set<ConstraintViolation<KnowledgeEntryRequest>> violations = validator.validate(req);
        assertTrue(hasViolation(violations, "title"), "空标题应触发校验违规");
    }

    @Test
    void knowledgeRequest_blankCategory_violation() {
        KnowledgeEntryRequest req = validKnowledgeRequest();
        req.setCategory(" ");
        Set<ConstraintViolation<KnowledgeEntryRequest>> violations = validator.validate(req);
        assertTrue(hasViolation(violations, "category"), "空白分类应触发校验违规");
    }

    @Test
    void knowledgeRequest_blankSourceType_violation() {
        KnowledgeEntryRequest req = validKnowledgeRequest();
        req.setSourceType("");
        Set<ConstraintViolation<KnowledgeEntryRequest>> violations = validator.validate(req);
        assertTrue(hasViolation(violations, "sourceType"), "空来源类型应触发校验违规");
    }

    @Test
    void knowledgeRequest_blankContent_violation() {
        KnowledgeEntryRequest req = validKnowledgeRequest();
        req.setContent("");
        Set<ConstraintViolation<KnowledgeEntryRequest>> violations = validator.validate(req);
        assertTrue(hasViolation(violations, "content"), "空内容应触发校验违规");
    }

    @Test
    void knowledgeRequest_nullExpireAt_violation() {
        KnowledgeEntryRequest req = validKnowledgeRequest();
        req.setExpireAt(null);
        Set<ConstraintViolation<KnowledgeEntryRequest>> violations = validator.validate(req);
        assertTrue(hasViolation(violations, "expireAt"), "空失效时间应触发校验违规");
    }

    @Test
    void caseRequest_valid_noViolation() {
        IndustryCaseRequest req = IndustryCaseRequest.builder().title("拿下某银行大单").build();
        Set<ConstraintViolation<IndustryCaseRequest>> violations = validator.validate(req);
        assertTrue(violations.isEmpty(), "合法案例请求不应有校验违规");
    }

    @Test
    void caseRequest_blankTitle_violation() {
        IndustryCaseRequest req = IndustryCaseRequest.builder().title("").build();
        Set<ConstraintViolation<IndustryCaseRequest>> violations = validator.validate(req);
        assertTrue(hasViolation(violations, "title"), "空标题应触发校验违规");
    }

    private <T> boolean hasViolation(Set<ConstraintViolation<T>> violations, String propertyPath) {
        return violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(propertyPath));
    }
}
