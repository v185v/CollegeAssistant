package xyz.v185v.collegeassistant.admin.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import xyz.v185v.collegeassistant.admin.audit.AdminAuditEvent;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditService;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserAccount;
import xyz.v185v.collegeassistant.auth.UserRepository;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;

class AdminUserServiceTest {

    private static final AuthPrincipal ACTOR =
            new AuthPrincipal(1L, "operator", UserRole.ADMIN.name());
    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.parse("2026-07-25T00:00:00Z");

    private UserRepository repository;
    private AdminAuditService auditService;
    private AdminUserService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserRepository.class);
        auditService = mock(AdminAuditService.class);
        when(auditService.audited(any(), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        service = new AdminUserService(repository, auditService);
    }

    @Test
    void pagesSafeUserSummaries() {
        UserAccount account = account(7L, UserRole.STUDENT, true, 3);
        when(repository.findPage(2, 20))
                .thenReturn(new PageResult<>(List.of(account), 21, 2, 20, 2));

        PageResult<AdminUserDto> result = service.list(ACTOR, 2, 20);

        assertThat(result.items()).containsExactly(new AdminUserDto(
                7L, "user-7", "User 7", UserRole.STUDENT, true, 3, CREATED_AT));
        assertThat(result.total()).isEqualTo(21);
    }

    @Test
    void demotingFinalEnabledAdministratorFailsInsideAuditBoundary() {
        UserAccount onlyAdmin = account(9L, UserRole.ADMIN, true, 4);
        when(repository.lockById(9L)).thenReturn(Optional.of(onlyAdmin));
        when(repository.countEnabledAdmins()).thenReturn(1L);

        assertThatThrownBy(() -> service.setRole(ACTOR, 9L, UserRole.STUDENT))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.LAST_ADMIN_REQUIRED));

        InOrder order = inOrder(repository);
        order.verify(repository).lockAdminRoleState();
        order.verify(repository).lockById(9L);
        order.verify(repository).countEnabledAdmins();
        verify(repository, never()).updateRoleAndIncrementTokenVersion(any(), any());
        assertAudit("USER_ROLE_CHANGED", "USER", 9L, "role", "STUDENT");
    }

    @Test
    void disablingFinalEnabledAdministratorIsRejected() {
        UserAccount onlyAdmin = account(9L, UserRole.ADMIN, true, 4);
        when(repository.lockById(9L)).thenReturn(Optional.of(onlyAdmin));
        when(repository.countEnabledAdmins()).thenReturn(1L);

        assertThatThrownBy(() -> service.setEnabled(ACTOR, 9L, false))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.LAST_ADMIN_REQUIRED));

        verify(repository, never()).updateEnabledAndIncrementTokenVersion(any(), any(Boolean.class));
        assertAudit("USER_ENABLED_CHANGED", "USER", 9L, "enabled", false);
    }

    @Test
    void roleMutationUsesLockAndReturnsIncrementedTokenVersion() {
        UserAccount student = account(7L, UserRole.STUDENT, true, 3);
        UserAccount promoted = account(7L, UserRole.ADMIN, true, 4);
        when(repository.lockById(7L)).thenReturn(Optional.of(student));
        when(repository.updateRoleAndIncrementTokenVersion(7L, UserRole.ADMIN))
                .thenReturn(promoted);

        AdminUserDto result = service.setRole(ACTOR, 7L, UserRole.ADMIN);

        assertThat(result.role()).isEqualTo(UserRole.ADMIN);
        assertThat(result.tokenVersion()).isEqualTo(4);
        verify(repository).lockAdminRoleState();
        assertAudit("USER_ROLE_CHANGED", "USER", 7L, "role", "ADMIN");
    }

    @Test
    void tokenInvalidationIncrementsVersionAndIsAudited() {
        UserAccount existing = account(7L, UserRole.STUDENT, true, 3);
        UserAccount invalidated = account(7L, UserRole.STUDENT, true, 4);
        when(repository.lockById(7L)).thenReturn(Optional.of(existing));
        when(repository.incrementTokenVersion(7L)).thenReturn(invalidated);

        AdminUserDto result = service.invalidateTokens(ACTOR, 7L);

        assertThat(result.tokenVersion()).isEqualTo(4);
        assertAudit("USER_TOKENS_INVALIDATED", "USER", 7L, null, null);
    }

    private void assertAudit(
            String action,
            String targetType,
            Long targetId,
            String summaryKey,
            Object summaryValue
    ) {
        ArgumentCaptor<AdminAuditEvent> event = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(auditService).audited(event.capture(), any());
        assertThat(event.getValue().actorId()).isEqualTo(ACTOR.userId());
        assertThat(event.getValue().action()).isEqualTo(action);
        assertThat(event.getValue().targetType()).isEqualTo(targetType);
        assertThat(event.getValue().targetId()).isEqualTo(targetId);
        if (summaryKey == null) {
            assertThat(event.getValue().summary()).isEmpty();
        } else {
            assertThat(event.getValue().summary()).containsEntry(summaryKey, summaryValue);
        }
    }

    private UserAccount account(
            Long id,
            UserRole role,
            boolean enabled,
            long tokenVersion
    ) {
        return new UserAccount(
                id,
                "user-" + id,
                "password-hash-must-not-leak",
                "User " + id,
                role,
                CREATED_AT,
                enabled,
                tokenVersion);
    }
}
