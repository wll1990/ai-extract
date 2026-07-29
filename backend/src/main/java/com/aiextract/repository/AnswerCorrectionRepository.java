package com.aiextract.repository;

import com.aiextract.model.AnswerCorrection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * @author AI Extract Team
 * @since 2026-07-30
 */
public interface AnswerCorrectionRepository extends JpaRepository<AnswerCorrection, UUID> {
}
