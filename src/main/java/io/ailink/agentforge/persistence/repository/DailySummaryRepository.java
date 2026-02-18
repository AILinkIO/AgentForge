package io.ailink.agentforge.persistence.repository;

import io.ailink.agentforge.persistence.entity.DailySummaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 每日总结数据访问层
 *
 * 提供对 daily_summaries 表的数据库操作。
 * 继承 JpaRepository 提供基本的 CRUD 操作。
 *
 * 主要查询方法：
 * - 按日期查询总结
 * - 按日期倒序查询所有总结
 */
@Repository
public interface DailySummaryRepository extends JpaRepository<DailySummaryEntity, Long> {

    /**
     * 根据日期查询每日总结
     *
     * @param summaryDate 总结日期
     * @return 找到的总结（可能为空）
     */
    Optional<DailySummaryEntity> findBySummaryDate(LocalDate summaryDate);

    /**
     * 查询所有每日总结，按日期倒序排列
     *
     * @return 按日期倒序的总结列表
     */
    List<DailySummaryEntity> findAllByOrderBySummaryDateDesc();
}
