package io.ailink.agentforge.repository;

import io.ailink.agentforge.entity.DailySummaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface DailySummaryRepository extends JpaRepository<DailySummaryEntity, Long> {

    Optional<DailySummaryEntity> findBySummaryDate(LocalDate summaryDate);

    java.util.List<DailySummaryEntity> findAllByOrderBySummaryDateDesc();
}
