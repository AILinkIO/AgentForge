package io.ailink.agentforge.persistence.store;

import io.ailink.agentforge.bitcask.Bitcask;
import io.ailink.agentforge.bitcask.BitcaskRuntime;
import io.ailink.agentforge.persistence.entity.ChatMessageEntity;
import io.ailink.agentforge.persistence.entity.DailySummaryEntity;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BitcaskHistoryStoreTest {

    @TempDir
    Path dir;

    @Test
    void roundTripAndReopen() {
        Assumptions.assumeTrue(BitcaskRuntime.unavailableReason() == null, "libbitcask 不可用");
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        try (Bitcask db = Bitcask.open(dir)) {
            BitcaskHistoryStore s = new BitcaskHistoryStore(db);
            ChatMessageEntity old = new ChatMessageEntity("user", "昨天的话");
            old.setCreatedAt(yesterday);
            s.save(old);
            s.save(new ChatMessageEntity("user", "你好"));
            s.save(new ChatMessageEntity("assistant", "你好，有什么可以帮你？"));
            assertEquals(3, s.count());
            assertEquals(2, s.findRecent(2).size());
            assertEquals("你好", s.findRecent(2).get(0).getContent());
            LocalDate today = LocalDate.now();
            assertEquals(2, s.findBetween(today.atStartOfDay(), today.atTime(23, 59, 59)).size());
            s.saveSummary(new DailySummaryEntity(today, "打了个招呼", 2));
            assertEquals("打了个招呼", s.findSummary(today).orElseThrow().getSummary());
        }
        try (Bitcask db = Bitcask.open(dir)) {
            BitcaskHistoryStore s = new BitcaskHistoryStore(db);
            ChatMessageEntity m = s.save(new ChatMessageEntity("user", "重开之后"));
            assertEquals(4L, m.getId(), "id 接着上次的计数");
            assertEquals(4, s.findAll().size());
            assertTrue(s.findAll().get(0).getCreatedAt().isBefore(s.findAll().get(1).getCreatedAt()));
            assertEquals(1, s.findAllSummaries().size());
        }
    }
}
