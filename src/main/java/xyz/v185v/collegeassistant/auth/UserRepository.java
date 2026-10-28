package xyz.v185v.collegeassistant.auth;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import xyz.v185v.collegeassistant.auth.persistence.UserAccountDO;
import xyz.v185v.collegeassistant.auth.persistence.UserAccountMapper;
import xyz.v185v.collegeassistant.common.PageResult;

/**
 * 用户账号持久化仓储，封装 {@code users} 表的查询与写入。
 */
@Repository
public class UserRepository {

    private final UserAccountMapper mapper;

    public UserRepository(UserAccountMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 按用户名查询账号。
     *
     * @param username 用户名
     * @return 存在时返回账号，否则返回空值
     */
    public Optional<UserAccount> findByUsername(String username) {
        UserAccountDO row = mapper.selectOne(Wrappers.<UserAccountDO>lambdaQuery()
                .eq(UserAccountDO::getUsername, username));
        return Optional.ofNullable(row).map(this::mapUser);
    }

    /**
     * 按主键查询账号。
     *
     * @param id 用户编号
     * @return 存在时返回账号，否则返回空值
     */
    public Optional<UserAccount> findById(Long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(this::mapUser);
    }

/** 列出用于治理的账户，不改变其持久化排序。 */
    public PageResult<UserAccount> findPage(int page, int size) {
        Page<UserAccountDO> result = mapper.selectPage(
                new Page<>(page, size),
                Wrappers.<UserAccountDO>lambdaQuery()
                        .orderByDesc(UserAccountDO::getCreatedAt)
                        .orderByDesc(UserAccountDO::getId));
        long total = result.getTotal();
        List<UserAccount> items = result.getRecords().stream().map(this::mapUser).toList();
        int pages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageResult<>(List.copyOf(items), total, page, size, pages);
    }

/** 为当前事务锁定管理员角色状态命名空间。 */
    public void lockAdminRoleState() {
        mapper.lockAdminRoleState();
    }

/** 在决定治理变更期间锁定一个账户。 */
    public Optional<UserAccount> lockById(Long id) {
        return Optional.ofNullable(mapper.lockById(id)).map(this::mapUser);
    }

/** 在共享咨询锁下统计当前可用的管理员账户数。 */
    public long countEnabledAdmins() {
        return mapper.selectCount(Wrappers.<UserAccountDO>lambdaQuery()
                .eq(UserAccountDO::getRole, UserRole.ADMIN.name())
                .eq(UserAccountDO::getEnabled, true));
    }

/** 更新角色，并使从账户旧状态签发的所有令牌失效。 */
    public UserAccount updateRoleAndIncrementTokenVersion(Long id, UserRole role) {
        return mapUser(mapper.updateRoleAndIncrementTokenVersion(id, role.name()));
    }

/** 更新账户可用性，并使之前签发的所有令牌失效。 */
    public UserAccount updateEnabledAndIncrementTokenVersion(Long id, boolean enabled) {
        return mapUser(mapper.updateEnabledAndIncrementTokenVersion(id, enabled));
    }

/** 在不改变角色或可用性的情况下使账户的所有令牌失效。 */
    public UserAccount incrementTokenVersion(Long id) {
        return mapUser(mapper.incrementTokenVersion(id));
    }

    /** 判断数据库中是否已经存在可登录的管理员。 */
    public boolean hasEnabledAdmin() {
        return countEnabledAdmins() > 0;
    }

    /**
     * 在数据库级管理员角色锁下建立首个管理员。
     *
     * <p>方法取得事务级咨询锁后重新检查启用管理员，因此多个实例同时启动时
     * 最多只有一个配置用户会被创建或提升。</p>
     *
     * @return 本次调用是否创建或提升了管理员
     */
    @Transactional
    public boolean bootstrapAdmin(String username, String passwordHash) {
        requireNonBlank(username, "Bootstrap username");
        requireNonBlank(passwordHash, "Bootstrap password hash");
        lockAdminRoleState();
        if (hasEnabledAdmin()) {
            return false;
        }

        return mapper.bootstrapAdmin(username, passwordHash) == 1;
    }

    /**
     * 创建用户账号。
     *
     * @param username     用户名
     * @param passwordHash BCrypt 密码摘要
     * @param displayName  展示名称
     * @param role         初始角色
     * @return 数据库生成完整字段后的账号
     */
    public UserAccount create(String username, String passwordHash, String displayName, UserRole role) {
        return mapUser(mapper.insertReturning(
                username, passwordHash, displayName, role.name()));
    }

    private void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private UserAccount mapUser(UserAccountDO row) {
        return new UserAccount(
                row.getId(),
                row.getUsername(),
                row.getPasswordHash(),
                row.getDisplayName(),
                UserRole.valueOf(row.getRole()),
                row.getCreatedAt(),
                row.getEnabled(),
                row.getTokenVersion()
        );
    }
}
