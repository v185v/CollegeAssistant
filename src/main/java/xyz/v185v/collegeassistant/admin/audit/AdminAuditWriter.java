package xyz.v185v.collegeassistant.admin.audit;

import org.springframework.stereotype.Component;

/** 始终参与调用方所需事务的成功路径写入器。 */
@Component
public class AdminAuditWriter {

    private final AdminAuditRepository repository;
    private final AdminAuditRedactor redactor;

    public AdminAuditWriter(AdminAuditRepository repository, AdminAuditRedactor redactor) {
        this.repository = repository;
        this.redactor = redactor;
    }

    public void writeInCurrentTransaction(AdminAuditEvent event) {
        repository.insert(redactor.redact(event));
    }
}
