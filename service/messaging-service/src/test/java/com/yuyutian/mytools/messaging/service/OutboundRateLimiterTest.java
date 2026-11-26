package com.yuyutian.mytools.messaging.service;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class OutboundRateLimiterTest {
    private OutboundRateLimiter limiter;
    private JdbcTemplate jdbc;
    private final Instant now=Instant.parse("2026-10-18T00:00:00Z");
    @BeforeEach void setup(){
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:outbound-rate-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(source);jdbc.execute("CREATE TABLE messaging_channel_budget(bucket_key CHAR(64) PRIMARY KEY,next_start_ms BIGINT NOT NULL)");
        limiter=new OutboundRateLimiter(jdbc,new TransactionTemplate(new DataSourceTransactionManager(source)));
    }
    @Test void concurrentReservationsShareOneWebhookBudgetWithoutStoringItsSecret() throws Exception {
        try(var pool=Executors.newFixedThreadPool(5)){
            List<Future<Long>> tasks=new ArrayList<>();for(int i=0;i<5;i++)tasks.add(pool.submit(()->limiter.reserve("https://example.test/private-webhook",now)));
            List<Long> waits=new ArrayList<>();for(var task:tasks)waits.add(task.get());
            assertThat(waits).containsExactlyInAnyOrder(0L,1100L,2200L,3300L,4400L);
        }
        assertThat(jdbc.queryForObject("SELECT bucket_key FROM messaging_channel_budget",String.class)).doesNotContain("private-webhook");
        assertThat(limiter.reserve("https://example.test/other",now)).isZero();
    }
    @Test void excessiveBacklogFailsBeforeSendingAndDoesNotConsumeMoreBudget(){
        for(int i=0;i<10;i++)limiter.reserve("same-webhook",now);
        long prior=jdbc.queryForObject("SELECT next_start_ms FROM messaging_channel_budget",Long.class);
        assertThatThrownBy(()->limiter.reserve("same-webhook",now)).isInstanceOf(OutboundException.class);
        assertThat(jdbc.queryForObject("SELECT next_start_ms FROM messaging_channel_budget",Long.class)).isEqualTo(prior);
        assertThat(limiter.reserve("same-webhook",now.plusSeconds(12))).isZero();
    }
}
