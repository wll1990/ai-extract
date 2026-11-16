package com.aiextract.repository;

import com.aiextract.model.ImBindCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface ImBindCodeRepository extends JpaRepository<ImBindCode, String> {

    @Modifying
    @Query("DELETE FROM ImBindCode c WHERE c.expiresAt < :now")
    int deleteExpired(LocalDateTime now);
}
