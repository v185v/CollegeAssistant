package xyz.v185v.collegeassistant.common;

/** API 业务状态码常量。 */
public final class ErrorCode {

    public static final int OK = 0;
    public static final int BUSINESS_ERROR = 40000;
    public static final int VALIDATION_FAILED = 40001;
    public static final int REQUEST_BODY_INVALID = 40002;
    public static final int UNSUPPORTED_OPERATION = 40003;
    public static final int UNAUTHORIZED = 40100;
    public static final int TOKEN_MISSING = 40101;
    public static final int TOKEN_EXPIRED = 40102;
    public static final int TOKEN_INVALID = 40103;
    public static final int FORBIDDEN = 40300;
    public static final int ADMIN_REQUIRED = 40301;
    public static final int RESOURCE_NOT_FOUND = 40400;
    public static final int CHAT_SESSION_NOT_FOUND = 40401;
    public static final int UGC_NOT_FOUND = 40402;
    public static final int SOURCE_NOT_FOUND = 40403;
    public static final int CONFLICT = 40900;
    public static final int USERNAME_EXISTS = 40901;
    public static final int UGC_ALREADY_REVIEWED = 40902;
    public static final int INGEST_ALREADY_RUNNING = 40903;
    public static final int DOCUMENT_REPLACEMENT_REQUIRED = 40904;
    public static final int LAST_ADMIN_REQUIRED = 40905;
    public static final int BACKUP_ALREADY_RUNNING = 40907;
    public static final int BACKUP_CAPACITY_EXCEEDED = 40908;
    public static final int EMBEDDING_REBUILD_IN_PROGRESS = 40909;
    public static final int RATE_LIMITED = 42900;
    public static final int INTERNAL_ERROR = 50000;
    public static final int MODEL_PROVIDER_FAILED = 50010;
    public static final int EMBEDDING_FAILED = 50011;
    public static final int STORAGE_FAILED = 50020;
    public static final int INGEST_FAILED = 50030;
    public static final int BACKUP_CAPABILITY_UNAVAILABLE = 50301;
    public static final int MODEL_CONFIG_UNAVAILABLE = 50302;

    private ErrorCode() {
    }
}
