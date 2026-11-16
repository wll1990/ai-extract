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
 * 门户「预约演示」留资。
 *
 * <p>由未登录访客提交，运营在后台跟进；不含任何租户业务数据。</p>
 */
@Entity
@Table(name = "demo_request")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DemoRequest {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_name", nullable = false, length = 200)
    private String companyName;

    @Column(name = "contact_name", nullable = false, length = 100)
    private String contactName;

    @Column(name = "role", length = 100)
    private String role;

    @Column(name = "team_size", length = 50)
    private String teamSize;

    @Column(name = "contact", nullable = false, length = 200)
    private String contact;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "source", nullable = false, length = 50)
    private String source;

    /** new / contacted / closed */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
