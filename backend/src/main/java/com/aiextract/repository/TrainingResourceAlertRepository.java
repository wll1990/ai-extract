package com.aiextract.repository;

import com.aiextract.model.TrainingResourceAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TrainingResourceAlertRepository extends JpaRepository<TrainingResourceAlert, UUID> {

    List<TrainingResourceAlert> findByPlanIdAndStatusOrderByCreatedAtDesc(UUID planId, String status);

    List<TrainingResourceAlert> findByCompanyIdAndStatusOrderByCreatedAtDesc(UUID companyId, String status);

    Optional<TrainingResourceAlert> findFirstByTaskIdAndResourceIdAndStatus(
            UUID taskId, UUID resourceId, String status);
}
