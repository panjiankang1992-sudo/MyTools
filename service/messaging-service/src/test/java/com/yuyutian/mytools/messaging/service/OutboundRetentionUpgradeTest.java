package com.yuyutian.mytools.messaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.messaging.config.OutboundRetentionProperties;
import com.yuyutian.mytools.messaging.model.OutboundModels.*;
import com.yuyutian.mytools.messaging.repository.OutboundRepository;
import com.yuyutian.mytools.messaging.utils.outbound.OutboundSecurity;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.nio.file.Path;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class OutboundRetentionUpgradeTest {
    @TempDir Path directory;
    @Test void migrationPreservesLegacyBodyAndCompactionSurvivesDatabaseReopen() {
        JdbcDataSource data=data();
        Flyway.configure().dataSource(data).target("19").load().migrate();
        JdbcTemplate jdbc=new JdbcTemplate(data);ObjectMapper json=new ObjectMapper().findAndRegisterModules();

        Instant now=Instant.parse("2026-10-18T00:00:00Z"),old=now.minus(Duration.ofDays(100));
        UUID destination=UUID.randomUUID(),id=UUID.randomUUID();
        jdbc.update("INSERT INTO messaging_destination(id,owner_id,binding_key,channel,label,secret_cipher,verification_reference,enabled,created_ms,updated_ms) VALUES(?,77,'key','EMAIL','Inbox','fixture-cipher','fixture-only',TRUE,?,?)",destination.toString(),old.toEpochMilli(),old.toEpochMilli());
        Content content=new Content("Subject","Frozen body",null);
        Create request=new Create(77,"original-key",destination,content,OutboundSecurity.hash(content),old);
        String encoded;try{encoded=json.writeValueAsString(content);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new AssertionError(e);}
        jdbc.update("INSERT INTO messaging_outbound(id,owner_id,idempotency_key,destination_id,channel,content_json,payload_hash,status,expires_ms,next_attempt_ms,created_ms,updated_ms) VALUES(?,77,'original-key',?,'EMAIL',?,?,'ACCEPTED',?,?,?,?)",id.toString(),destination.toString(),encoded,request.payloadHash(),old.toEpochMilli(),old.toEpochMilli(),old.toEpochMilli(),old.toEpochMilli());
        jdbc.update("UPDATE messaging_outbound SET status='FAILED' WHERE id=?",id.toString());
        String before=jdbc.queryForObject("SELECT content_json FROM messaging_outbound WHERE id=?",String.class,id.toString());
        Flyway.configure().dataSource(data).load().migrate();
        assertThat(jdbc.queryForObject("SELECT content_json FROM messaging_outbound WHERE id=?",String.class,id.toString())).isEqualTo(before);
        var capacity=new com.yuyutian.mytools.messaging.repository.OutboundCapacityRepository(jdbc,new com.yuyutian.mytools.messaging.config.OutboundCapacityProperties(1000,100000,100000,536870912));
        OutboundRepository repository=new OutboundRepository(jdbc,json,capacity);
        assertThat(repository.get(id,false).orElseThrow().content()).isEqualTo(content);
        new OutboundRetentionService(jdbc,json,new DataSourceTransactionManager(data),new OutboundRetentionProperties(true,90,90,2),capacity).sweep(now);
        jdbc.execute("SHUTDOWN");
        JdbcTemplate reopened=new JdbcTemplate(data());
        Delivery record=new OutboundRepository(reopened,json,new com.yuyutian.mytools.messaging.repository.OutboundCapacityRepository(reopened,new com.yuyutian.mytools.messaging.config.OutboundCapacityProperties(1000,100000,100000,536870912))).byKey(77,"original-key").orElseThrow();
        assertThat(record.id()).isEqualTo(id);assertThat(record.content()).isNull();assertThat(record.status()).isEqualTo("FAILED");
        assertThat(record.contentIdentityHash()).isEqualTo(OutboundSecurity.identityHash(content));
        assertThat(record.payloadHash()).isEqualTo(request.payloadHash());
        assertThat(reopened.queryForObject("SELECT last_status FROM messaging_outbound_retention_state",String.class)).isEqualTo("SUCCEEDED");
        reopened.execute("SHUTDOWN");
    }
    private JdbcDataSource data(){JdbcDataSource data=new JdbcDataSource();data.setURL("jdbc:h2:file:"+directory.resolve("messages")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE");return data;}
}
