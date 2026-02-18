package io.ailink.agentforge.repository;

import io.ailink.agentforge.entity.ChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    List<ChatMessageEntity> findAllByOrderByCreatedAtAsc();

    @Query("SELECT m FROM ChatMessageEntity m WHERE m.createdAt >= :start AND m.createdAt < :end ORDER BY m.createdAt ASC")
    List<ChatMessageEntity> findByDateRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(LocalDateTime start, LocalDateTime end);

    List<ChatMessageEntity> findTop100ByOrderByCreatedAtDesc();

    List<ChatMessageEntity> findAllByOrderByCreatedAtDesc();
}
