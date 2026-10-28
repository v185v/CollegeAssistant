package xyz.v185v.collegeassistant.ugc;

/**
 * UGC 审核事务提交后触发向量索引的领域事件。
 *
 * @param postId 已审核通过的帖子编号
 */
public record UgcApprovedEvent(Long postId) {
}
