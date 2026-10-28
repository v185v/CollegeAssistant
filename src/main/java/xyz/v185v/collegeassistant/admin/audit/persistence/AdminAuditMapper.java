package xyz.v185v.collegeassistant.admin.audit.persistence;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import xyz.v185v.collegeassistant.admin.audit.AdminAuditFilter;

@Mapper
public interface AdminAuditMapper extends BaseMapper<AdminAuditLogDO> {

    void insertEvent(@Param("row") AdminAuditLogDO row);

    List<AdminAuditLogDO> find(@Param("filter") AdminAuditFilter filter,
                               @Param("offset") long offset,
                               @Param("size") int size);
}
