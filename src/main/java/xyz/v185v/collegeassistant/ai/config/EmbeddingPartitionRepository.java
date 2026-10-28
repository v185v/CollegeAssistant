package xyz.v185v.collegeassistant.ai.config;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 单个服务器编号 Embedding LIST 分区的事务性 DDL 边界。 */
@Repository
public class EmbeddingPartitionRepository {

    private final JdbcClient jdbcClient;

    public EmbeddingPartitionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void create(long configurationId) {
        String partition = partitionName(configurationId);
        jdbcClient.sql(
                        "CREATE TABLE " + partition
                                + " PARTITION OF embedding_chunk FOR VALUES IN ("
                                + configurationId + ')')
                .update();
    }

/** 仅删除某个 Embedding 配置对应的确定性子分区。 */
    public void drop(long configurationId) {
        String partition = partitionName(configurationId);
        jdbcClient.sql("DROP TABLE IF EXISTS " + partition).update();
    }

    public String partitionName(long configurationId) {
        if (configurationId <= 0) {
            throw new IllegalArgumentException("Embedding configuration id must be positive");
        }
        return "embedding_chunk_cfg_" + configurationId;
    }
}
