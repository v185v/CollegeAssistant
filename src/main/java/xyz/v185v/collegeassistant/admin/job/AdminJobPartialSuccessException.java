package xyz.v185v.collegeassistant.admin.job;

/** 表示任务包含已完成和失败工作项的终态结果。 */
public final class AdminJobPartialSuccessException extends RuntimeException {

    private static final int MAX_ERROR_CODE_LENGTH = 64;
    private static final int MAX_ERROR_SUMMARY_LENGTH = 1000;

    private final String errorCode;
    private final String errorSummary;

    public AdminJobPartialSuccessException(String errorCode, String errorSummary) {
        super("Administration job completed with partial success");
        this.errorCode = requireValue(errorCode, "errorCode", MAX_ERROR_CODE_LENGTH);
        this.errorSummary = requireValue(errorSummary, "errorSummary", MAX_ERROR_SUMMARY_LENGTH);
    }

    public String errorCode() {
        return errorCode;
    }

    public String errorSummary() {
        return errorSummary;
    }

    private static String requireValue(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be nonblank and at most "
                    + maximumLength + " characters");
        }
        return value;
    }
}
