package io.ailink.agentforge.persistence.repository;

import io.ailink.agentforge.persistence.entity.ChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 聊天消息数据访问层
 *
 * 提供对 chat_messages 表的数据库操作。
 * 继承 JpaRepository 提供基本的 CRUD 操作。
 *
 * 主要查询方法：
 * - 按创建时间排序查询所有消息
 * - 按日期范围查询消息
 * - 统计指定日期范围内的消息数量
 * - 获取最近的消息
 */
@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    /**
     * 查询所有消息，按创建时间升序排列
     *
     * @return 按时间排序的消息列表
     */
    List<ChatMessageEntity> findAllByOrderByCreatedAtAsc();

    /**
     * 按日期范围查询消息
     *
     * @param start 开始时间（包含）
     * @param end   结束时间（不包含）
     * @return 日期范围内的消息列表
     */
    @Query("SELECT m FROM ChatMessageEntity m WHERE m.createdAt >= :start AND m.createdAt < :end ORDER BY m.createdAt ASC")
    List<ChatMessageEntity> findByDateRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 统计指定日期范围内的消息数量
     *
     * @param start 开始时间（包含）
     * @param end   结束时间（不包含）
     * @return 消息数量
     */
    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(LocalDateTime start, LocalDateTime end);

    /**
     * 获取最近N条消息，按创建时间降序排列
     *
     * @param limit 返回的消息数量限制
     * @return 最近的消息列表
     */
    List<ChatMessageEntity> findTop100ByOrderByCreatedAtDesc();

    /**
     * 查询所有消息，按创建时间降序排列
     *
     * @return 按时间倒序的消息列表
     */
    List<ChatMessageEntity> findAllByOrderByCreatedAtDesc();
}
