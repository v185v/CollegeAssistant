package xyz.v185v.collegeassistant.ai;

import java.util.List;

/** 面向应用层的文本向量生成边界，隔离具体模型厂商类型。 */
public interface EmbeddingGateway {

    /**
     * 为单段非空文本生成向量。
     *
     * @param text 文本
     * @return 固定维度向量
     */
    List<Double> embed(String text);

    /**
     * 批量生成文本向量，返回顺序与输入一致。
     *
     * @param texts 非空文本列表
     * @return 向量列表
     */
    List<List<Double>> embedAll(List<String> texts);

    /** @return 当前网关输出的固定向量维度 */
    int dimension();
}
