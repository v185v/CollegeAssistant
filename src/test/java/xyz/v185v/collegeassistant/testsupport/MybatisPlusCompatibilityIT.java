package xyz.v185v.collegeassistant.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

class MybatisPlusCompatibilityIT extends DatabaseTestSupport {

    @Autowired MybatisSmokeMapper mapper;
    @Autowired JdbcClient jdbcClient;

    @Test
    @Transactional
    void mapperLoadsOnBoot41AndSharesTheJdbcTransaction() {
        assertThat(mapper.selectOne()).isOne();
        long id = jdbcClient.sql("""
                        INSERT INTO users (username, password_hash, role)
                        VALUES ('mybatis-smoke', 'hash', 'STUDENT')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();
        assertThat(mapper.countUserById(id)).isOne();
    }
}
