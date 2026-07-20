package com.aiextract.repository;

import com.aiextract.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * @author AI Extract Team
 */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    /**
     * 查询，按，account。
     * @param account 参数
     * @return 查询结果，可能为空
     */
    Optional<AppUser> findByAccount(String account);
    /**
     * 判断（Account）。
     * @param account account
     * @return 是否满足条件
     */
    boolean existsByAccount(String account);


}
