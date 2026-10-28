package xyz.v185v.collegeassistant.ai.config.persistence;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface ModelConfigurationMapper extends BaseMapper<ModelConfigurationDO> {

    List<ModelConfigurationDO> findAll();

    ModelConfigurationDO lockById(@Param("id") long id);

    long reserveId();

    ModelConfigurationDO insertReturning(@Param("configuration") ModelConfigurationDO configuration);

    int retirePending(@Param("modelKind") String modelKind);

    ModelConfigurationDO publishPendingReturning(@Param("configuration") ModelConfigurationDO configuration);

    ModelConfigurationDO activateChatReturning(@Param("id") long id);

    ModelConfigurationDO activateEmbeddingReturning(@Param("id") long id);

    ModelConfigurationDO retirePendingEmbeddingReturning(
            @Param("id") long id, @Param("reason") String reason);

    int markFailed(@Param("id") long id, @Param("reason") String reason);

    int markEmbeddingFailed(@Param("id") long id, @Param("reason") String reason);

    Long insertCleanupJob(@Param("configurationId") long configurationId);
}
