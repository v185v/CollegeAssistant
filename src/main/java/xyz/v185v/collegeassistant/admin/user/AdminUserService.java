package xyz.v185v.collegeassistant.admin.user;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import xyz.v185v.collegeassistant.admin.audit.AdminAuditEvent;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditService;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserAccount;
import xyz.v185v.collegeassistant.auth.UserRepository;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;

/** 对用户角色、可用性和令牌状态执行加锁的管理员操作。 */
@Service
public class AdminUserService {

    private final UserRepository userRepository;
    private final AdminAuditService auditService;

    public AdminUserService(UserRepository userRepository, AdminAuditService auditService) {
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    public PageResult<AdminUserDto> list(AuthPrincipal actor, int page, int size) {
        requireAdmin(actor);
        if (page < 1 || size < 1 || size > 100) {
            throw validation("Invalid user pagination");
        }
        PageResult<UserAccount> accounts = userRepository.findPage(page, size);
        List<AdminUserDto> items = accounts.items().stream().map(this::toDto).toList();
        return new PageResult<>(
                items, accounts.total(), accounts.page(), accounts.size(), accounts.pages());
    }

    @Transactional
    public AdminUserDto setRole(AuthPrincipal actor, Long userId, UserRole role) {
        requireAdmin(actor);
        Map<String, Object> summary = role == null
                ? Map.of() : Map.of("role", role.name());
        return audited(
                AdminAuditEvent.requested(
                        actor.userId(), "USER_ROLE_CHANGED", "USER", userId, summary),
                () -> {
                    if (role == null) {
                        throw validation("User role is required");
                    }
                    userRepository.lockAdminRoleState();
                    UserAccount target = requireLockedUser(userId);
                    if (target.enabled()
                            && target.role() == UserRole.ADMIN
                            && role != UserRole.ADMIN
                            && userRepository.countEnabledAdmins() <= 1) {
                        throw lastAdmin();
                    }
                    return toDto(userRepository.updateRoleAndIncrementTokenVersion(userId, role));
                });
    }

    @Transactional
    public AdminUserDto setEnabled(AuthPrincipal actor, Long userId, boolean enabled) {
        requireAdmin(actor);
        return audited(
                AdminAuditEvent.requested(
                        actor.userId(), "USER_ENABLED_CHANGED", "USER", userId,
                        Map.of("enabled", enabled)),
                () -> {
                    userRepository.lockAdminRoleState();
                    UserAccount target = requireLockedUser(userId);
                    if (!enabled
                            && target.enabled()
                            && target.role() == UserRole.ADMIN
                            && userRepository.countEnabledAdmins() <= 1) {
                        throw lastAdmin();
                    }
                    return toDto(userRepository.updateEnabledAndIncrementTokenVersion(
                            userId, enabled));
                });
    }

    @Transactional
    public AdminUserDto invalidateTokens(AuthPrincipal actor, Long userId) {
        requireAdmin(actor);
        return audited(
                AdminAuditEvent.requested(
                        actor.userId(), "USER_TOKENS_INVALIDATED", "USER", userId, Map.of()),
                () -> {
                    requireLockedUser(userId);
                    return toDto(userRepository.incrementTokenVersion(userId));
                });
    }

    private UserAccount requireLockedUser(Long userId) {
        return userRepository.lockById(userId).orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "User not found"));
    }

    private AdminUserDto toDto(UserAccount user) {
        return new AdminUserDto(
                user.id(),
                user.username(),
                user.displayName(),
                user.role(),
                user.enabled(),
                user.tokenVersion(),
                user.createdAt());
    }

    private <T> T audited(AdminAuditEvent event, Supplier<T> operation) {
        return auditService.audited(event, operation);
    }

    private void requireAdmin(AuthPrincipal actor) {
        if (actor == null || !UserRole.ADMIN.name().equals(actor.role())) {
            throw new BusinessException(ErrorCode.ADMIN_REQUIRED, "Administrator role required");
        }
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    private BusinessException lastAdmin() {
        return new BusinessException(
                ErrorCode.LAST_ADMIN_REQUIRED,
                "At least one administrator is required");
    }
}
