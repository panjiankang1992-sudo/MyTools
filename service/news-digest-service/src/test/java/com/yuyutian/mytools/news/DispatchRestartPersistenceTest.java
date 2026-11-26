package com.yuyutian.mytools.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.DeliveryModels.*;
import com.yuyutian.mytools.news.config.DeliveryProperties;
import com.yuyutian.mytools.news.mapper.DispatchRepository;
import com.yuyutian.mytools.news.service.NewsDeliveryRenderer;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DispatchRestartPersistenceTest {
    @TempDir Path directory;
    @Test void frozenPartsAndUnknownSubmissionSurviveDatabaseAndRepositoryRestart() throws Exception {
        String url="jdbc:h2:file:"+directory.resolve("news-delivery")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        var source=source(url);Flyway.configure().dataSource(source).load().migrate();
        var jdbc=new JdbcTemplate(source);var manager=new DataSourceTransactionManager(source);var tx=new TransactionTemplate(manager);
        var json=new ObjectMapper().findAndRegisterModules();
        var properties=new DeliveryProperties(true,"http://127.0.0.1:23250","x".repeat(32),"https://example.test/news");
        var first=new DispatchRepository(jdbc,manager,json,properties,new NewsDeliveryRenderer(json,properties), Fixtures.control(jdbc));
        UUID destination=UUID.randomUUID();
        var now=Fixtures.CUTOFF;var edition=NewsDeliveryRendererTest.edition("persisted-event",Fixtures.EVIDENCE);
        first.changeTargets(55,0,List.of(new Target(destination,Channel.EMAIL,RenderMode.FULL,true)),now);
        var scheduling=new com.yuyutian.mytools.news.mapper.SchedulingRepository(jdbc,manager,first,Fixtures.control(jdbc));
        scheduling.change(55,new com.yuyutian.mytools.news.Model.SchedulingModels.SubscriptionChange(0,true,"UTC","00:00"),now.minusSeconds(1));
        scheduling.scan(now,"fixture-node");
        UUID run=scheduling.runs(55,1).getFirst().id();

        jdbc.update("INSERT INTO news_content_identity(owner_id,idempotency_hash,request_hash,identity_kind) VALUES(55,'disk-key','disk-hash','LEGACY')");
        jdbc.update("UPDATE news_control_capacity SET preview_identities=preview_identities+1 WHERE id=1");
        jdbc.update("INSERT INTO news_edition(id,owner_id,idempotency_hash,request_hash,cutoff_ms,created_ms,quality,payload_json) VALUES(?,55,'disk-key','disk-hash',?,?,'COMPLETE',?)",edition.id().toString(),now.toEpochMilli(),now.toEpochMilli(),json.writeValueAsString(edition));
        tx.executeWithoutResult(status->first.publish(run,edition.id(),now));
        Claim abandoned=first.claim(now).orElseThrow();Dispatch before=first.part(abandoned.id(),false);
        jdbc.execute("SHUTDOWN");
        var reopened=source(url);var afterJdbc=new JdbcTemplate(reopened);
        var after=new DispatchRepository(afterJdbc,new DataSourceTransactionManager(reopened),json,properties,new NewsDeliveryRenderer(json,properties), Fixtures.control(afterJdbc));
        assertThat(after.part(before.id(),false)).isEqualTo(before);
        assertThat(after.claim(now.plusSeconds(30))).isEmpty();
        Claim recovered=after.claim(now.plusSeconds(61)).orElseThrow();
        assertThat(recovered.id()).isEqualTo(abandoned.id());assertThat(recovered.token()).isNotEqualTo(abandoned.token());
        assertThat(after.part(before.id(),false).payloadHash()).isEqualTo(before.payloadHash());
        assertThat(after.history(56)).isEmpty();assertThat(after.targets(55).revision()).isEqualTo(1);
        afterJdbc.execute("SHUTDOWN");
    }
    private JdbcDataSource source(String url){var data=new JdbcDataSource();data.setURL(url);return data;}
}
