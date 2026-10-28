package xyz.v185v.collegeassistant.storage;

import java.io.InputStream;
import java.util.List;

/** 原始文档对象存储抽象，可由本地文件系统或云存储实现。 */
public interface ObjectStorage {

    /**
* 执行有界且不读取内容的可用性探测。
     *
* @return 当前是否可以使用配置的存储根目录
     */
    boolean isAvailable();

    /**
     * 保存对象，但不关闭由调用方持有的输入流。
     *
     * @param namespace 对象命名空间
     * @param filename  原始文件名
     * @param input     对象内容输入流
     * @return 已保存对象的标识信息
     */
    StoredObject save(String namespace, String filename, InputStream input);

    /**
     * 打开对象；调用方必须关闭返回的输入流。
     *
     * @param objectKey 对象键
     * @return 对象内容输入流
     */
    InputStream open(String objectKey);

    /**
     * 删除对象；对象已不存在时不执行操作。
     *
     * @param objectKey 对象键
     */
    void delete(String objectKey);

    /**
* 返回命名空间族的不可变不透明元数据：精确命名空间，以及名称以
* {@code namespace + "-"} 开头的租约派生命名空间。
     */
    List<StoredObjectMetadata> list(String namespace);
}
