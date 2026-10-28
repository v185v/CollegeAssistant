package xyz.v185v.collegeassistant.testsupport;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MybatisSmokeMapper {
    int selectOne();

    long countUserById(@Param("id") long id);
}
