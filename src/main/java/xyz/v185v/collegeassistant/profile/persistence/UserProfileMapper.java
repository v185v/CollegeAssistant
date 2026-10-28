package xyz.v185v.collegeassistant.profile.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfileDO> {

    UserProfileDO upsert(@Param("profile") UserProfileDO profile);
}
