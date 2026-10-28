package xyz.v185v.collegeassistant.admin.document;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/** 导入条目发布前使用的私有、服务器寻址存储。 */
public interface ImportStagingStore {

    StagedImportObject save(long runId, long itemId, InputStream input);

    InputStream open(long runId, long itemId);

    List<StagedImportObject> findOlderThan(Instant cutoff);

    void delete(long runId, long itemId);
}
