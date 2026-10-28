package xyz.v185v.collegeassistant.admin.source;

import java.util.List;

import xyz.v185v.collegeassistant.document.SourceRecord;

/** 连接器预览扩展点；任务 9 会用连接器路由器替换单 URL 适配器。 */
public interface SourcePreviewGateway {

    List<SourcePreviewItem> preview(SourceRecord source);
}
