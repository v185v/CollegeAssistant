package xyz.v185v.collegeassistant.admin.audit;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 与已回滚领域事务隔离的失败路径写入器。 */
@Component
public class AdminAuditFailureWriter {

    private final AdminAuditRepository repository;
    private final AdminAuditRedactor redactor;

    public AdminAuditFailureWriter(
            AdminAuditRepository repository,
            AdminAuditRedactor redactor
    ) {
        this.repository = repository;
        this.redactor = redactor;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeFailure(AdminAuditEvent event) {
        repository.insert(redactor.redact(event));
    }
}
