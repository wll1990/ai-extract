package com.aiextract.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 颗粒编辑历史 —— Admin 每次修改颗粒时自动记录，支持版本回滚。
  * @author AI Extract Team
 */
@Entity
@Table(name = "grain_edit_history")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class GrainEditHistory {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "grain_id", nullable = false)
    private UUID grainId;

    @Column(name = "field_name", nullable = false, length = 50)
    private String fieldName;

    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    @Column(name = "edited_by", length = 100)
    private String editedBy;

    @Column(name = "edit_note", columnDefinition = "TEXT")
    private String editNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
