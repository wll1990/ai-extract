package com.aiextract.repository;

import com.aiextract.model.CompanySetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CompanySettingRepository extends JpaRepository<CompanySetting, UUID> {

    Optional<CompanySetting> findByCompanyIdAndSettingKey(UUID companyId, String settingKey);

    /**
     * 原子 upsert：并发首次保存同一开关时由唯一约束收敛，避免「先查后插」的竞态。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO public.company_setting "
            + "(id, company_id, setting_key, setting_value, updated_by, updated_at) "
            + "VALUES (:id, :companyId, :settingKey, :settingValue, :updatedBy, :updatedAt) "
            + "ON CONFLICT (company_id, setting_key) DO UPDATE SET "
            + "setting_value = EXCLUDED.setting_value, "
            + "updated_by = EXCLUDED.updated_by, "
            + "updated_at = EXCLUDED.updated_at", nativeQuery = true)
    int upsert(@Param("id") UUID id,
               @Param("companyId") UUID companyId,
               @Param("settingKey") String settingKey,
               @Param("settingValue") String settingValue,
               @Param("updatedBy") UUID updatedBy,
               @Param("updatedAt") LocalDateTime updatedAt);
}
