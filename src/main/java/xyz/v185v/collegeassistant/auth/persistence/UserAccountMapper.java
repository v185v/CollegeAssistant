package xyz.v185v.collegeassistant.auth.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccountDO> {

    String lockAdminRoleState();

    UserAccountDO lockById(@Param("id") Long id);

    UserAccountDO updateRoleAndIncrementTokenVersion(
            @Param("id") Long id,
            @Param("role") String role);

    UserAccountDO updateEnabledAndIncrementTokenVersion(
            @Param("id") Long id,
            @Param("enabled") boolean enabled);

    UserAccountDO incrementTokenVersion(@Param("id") Long id);

    int bootstrapAdmin(
            @Param("username") String username,
            @Param("passwordHash") String passwordHash);

    UserAccountDO insertReturning(
            @Param("username") String username,
            @Param("passwordHash") String passwordHash,
            @Param("displayName") String displayName,
            @Param("role") String role);
}
