package xyz.v185v.collegeassistant.storage;

/**
 * 已保存对象的标识信息。
 *
 * @param objectKey        存储层对象键
 * @param originalFilename 原始文件名
 * @param size             对象字节数
 */
public record StoredObject(
        String objectKey,
        String originalFilename,
        long size
) {
}
