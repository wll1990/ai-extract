package com.aiextract.repository;

import com.aiextract.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 用户数据访问接口
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * 根据企业ID和账号查询用户
     *
     * @param companyId 企业ID
     * @param account   登录账号
     * @return 用户（可能为空）
     */
    Optional<User> findByCompanyIdAndAccount(UUID companyId, String account);

    /**
     * 判断指定企业内账号是否已存在
     *
     * @param companyId 企业ID
     * @param account   登录账号
     * @return true表示已存在
     */
    boolean existsByCompanyIdAndAccount(UUID companyId, String account);
    /**
     * 查询，按，company，id。
     * @param companyId 参数
     * @return 查询结果列表
     */
    List<User> findByCompanyId(UUID companyId);
    org.springframework.data.domain.Page<User> findByCompanyId(UUID companyId, org.springframework.data.domain.Pageable pageable);

    /** 通过 spaceId 直取 userName，避免 space→user 两次查询 */
    @org.springframework.data.jpa.repository.Query(
        "SELECT u.name FROM User u WHERE u.id = (SELECT s.userId FROM Space s WHERE s.id = :spaceId)")
    /**
     * 查询（Name,Space,Id）。
     * @param spaceId spaceId
     * @return 查询结果
     */
    java.util.Optional<String> findNameBySpaceId(@org.springframework.data.repository.query.Param("spaceId") UUID spaceId);


}
