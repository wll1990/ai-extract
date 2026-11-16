package com.aiextract.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 线索联系人。一个线索最多一个主联系人（部分唯一索引兜底）。
 */
@Entity
@Table(name = "lead_contact")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeadContact {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "lead_id", nullable = false)
    private UUID leadId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "phone_digits", length = 30)
    private String phoneDigits;

    /** decision_maker / influencer / user / gatekeeper / other */
    @Column(name = "role", length = 30)
    private String role;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static final String ROLE_DECISION_MAKER = "decision_maker";
    public static final String ROLE_INFLUENCER = "influencer";
    public static final String ROLE_USER = "user";
    public static final String ROLE_GATEKEEPER = "gatekeeper";
    public static final String ROLE_OTHER = "other";
}
