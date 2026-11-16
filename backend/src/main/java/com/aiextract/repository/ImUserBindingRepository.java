package com.aiextract.repository;

import com.aiextract.model.ImUserBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ImUserBindingRepository extends JpaRepository<ImUserBinding, UUID> {

    List<ImUserBinding> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<ImUserBinding> findByChannelTypeAndExternalUserId(String channelType, String externalUserId);

    Optional<ImUserBinding> findByCompanyIdAndChannelTypeAndUserId(
            UUID companyId, String channelType, UUID userId);

    Optional<ImUserBinding> findByIdAndUserId(UUID id, UUID userId);

    List<ImUserBinding> findByCompanyIdOrderByCreatedAtDesc(UUID companyId);

    @Modifying
    @Query("UPDATE ImUserBinding b SET b.lastSeenAt = :at, b.chatId = COALESCE(:chatId, b.chatId) "
            + "WHERE b.id = :id")
    int touch(@Param("id") UUID id, @Param("chatId") String chatId,
              @Param("at") LocalDateTime at);
}
