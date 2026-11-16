package com.aiextract.repository;

import com.aiextract.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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
     * 判断用户是否属于指定企业（跨租户资源归属校验用）。
     *
     * @param id        用户 ID
     * @param companyId 企业 ID
     * @return true 表示该用户属于该企业
     */
    boolean existsByIdAndCompanyId(UUID id, UUID companyId);
    /**
     * 查询，按，company，id。
     * @param companyId 参数
     * @return 查询结果列表
     */
    List<User> findByCompanyId(UUID companyId);

    /** 企业内某类角色的活跃用户（通知路由用；管理员通常很少） */
    List<User> findByCompanyIdAndRoleInAndIsActiveTrue(UUID companyId, java.util.Collection<String> roles);

    /**
     * 选择器：企业内活跃用户，按角色集合/姓名/账号搜索。
     *
     * <p>角色用集合而不是可空单值：调用方必须显式给出允许的角色（B 端三类），
     * 否则会搜出同企业挂靠的 C 端用户并被批量分配成学员。</p>
     */
    @Query("SELECT u FROM User u WHERE u.companyId = :companyId AND u.isActive = true "
            + "AND u.role IN :roles "
            // keyword 是 varchar：显式 cast，否则 JDBC 空参会按 bytea 绑定，PostgreSQL 报
            // "operator does not exist: character varying ~~ bytea"
            + "AND (CAST(:keyword AS string) IS NULL "
            + "     OR u.name LIKE CONCAT('%', CAST(:keyword AS string), '%') "
            + "     OR u.account LIKE CONCAT('%', CAST(:keyword AS string), '%')) "
            + "ORDER BY u.name, u.id")
    Page<User> searchForPicker(@Param("companyId") UUID companyId,
                               @Param("roles") java.util.Collection<String> roles,
                               @Param("keyword") String keyword,
                               Pageable pageable);

    /** 选择器（超管）：不限企业，同样只允许 B 端角色 */
    @Query("SELECT u FROM User u WHERE u.isActive = true "
            + "AND u.role IN :roles "
            + "AND (CAST(:keyword AS string) IS NULL "
            + "     OR u.name LIKE CONCAT('%', CAST(:keyword AS string), '%') "
            + "     OR u.account LIKE CONCAT('%', CAST(:keyword AS string), '%')) "
            + "ORDER BY u.companyId, u.name, u.id")
    Page<User> searchForPickerAll(@Param("roles") java.util.Collection<String> roles,
                                  @Param("keyword") String keyword,
                                  Pageable pageable);
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

    // ========== C 端查询 ==========

    /** 按 account 全局查找（C 端登录/注册查重，需配合 isCEnd() 过滤 B 端） */
    Optional<User> findByAccount(String account);

    /** account 是否已被 C 端用户占用 */
    boolean existsByAccount(String account);

    /** 按分享来源查用户（归因分析） */
    List<User> findBySourceShareId(UUID sourceShareId);

    // ========== 鉴权撤销 ==========

    /**
     * 安全版本号 +1 —— 改角色/停用账号时调用，使旧 token 立即失效。
     *
     * <p>返回 0 表示用户不存在；调用方应据此判断是否需要写 Redis。</p>
     */
    // clearAutomatically：批量 UPDATE 不刷新一级缓存；同一事务里若有旧 User 实体被保存，
    // 全列 UPDATE 会把 token_version 写回旧值，导致旧 token 继续可用
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
        "UPDATE User u SET u.tokenVersion = u.tokenVersion + 1 WHERE u.id = :id")
    int bumpTokenVersion(@org.springframework.data.repository.query.Param("id") UUID id);

    /** 读取当前安全版本号（bump 后取新值写 Redis 缓存） */
    @org.springframework.data.jpa.repository.Query(
        "SELECT u.tokenVersion FROM User u WHERE u.id = :id")
    Long findTokenVersionById(@org.springframework.data.repository.query.Param("id") UUID id);

    /** 平台运营账号 —— 公开留资需要通知到人。 */
    List<User> findByRoleAndIsActiveTrue(String role);
}
