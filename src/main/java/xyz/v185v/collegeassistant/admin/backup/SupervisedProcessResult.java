package xyz.v185v.collegeassistant.admin.backup;

/** 仅在两个有界读取器都完成后公开的子进程终态结果。 */
public record SupervisedProcessResult(
        int exitCode,
        BoundedProcessOutput stdout,
        BoundedProcessOutput stderr
) {
}
