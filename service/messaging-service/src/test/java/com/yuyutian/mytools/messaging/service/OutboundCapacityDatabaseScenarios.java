package com.yuyutian.mytools.messaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.messaging.config.OutboundCapacityProperties;
import com.yuyutian.mytools.messaging.config.OutboundRetentionProperties;
import com.yuyutian.mytools.messaging.model.ErrorCode;
import com.yuyutian.mytools.messaging.model.OutboundModels.*;
import com.yuyutian.mytools.messaging.repository.OutboundCapacityRepository;
import com.yuyutian.mytools.messaging.repository.OutboundRepository;
import com.yuyutian.mytools.messaging.utils.outbound.OutboundSecurity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** H2 与隔离 MySQL 共用真实消息仓储场景，不调用邮件、飞书或调度服务。 */
public final class OutboundCapacityDatabaseScenarios {
    private static final Instant NOW=Instant.parse("2026-10-18T00:00:00Z");
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private OutboundCapacityDatabaseScenarios() { }
    /** 在 V20 构造有正文、未知结果和已压缩身份的存量数据。 */
    public static void seedLegacy(JdbcTemplate jdbc) throws Exception {
        UUID destination=UUID.randomUUID();
        jdbc.update("INSERT INTO messaging_destination(id,owner_id,binding_key,channel,label,secret_cipher,verification_reference,enabled,created_ms,updated_ms) VALUES(?,77,'inbox','EMAIL','Inbox','fixture-cipher','fixture-only',TRUE,0,0)",destination.toString());
        Content content=new Content("Fixture","Frozen \uD83D\uDE80",null);
        for(String state:List.of("FAILED","UNKNOWN","CANCELLED")) {
            jdbc.update("INSERT INTO messaging_outbound(id,owner_id,idempotency_key,destination_id,channel,content_json,payload_hash,status,expires_ms,next_attempt_ms,created_ms,updated_ms,content_identity_hash,content_expired_ms) VALUES(?,77,?,?,'EMAIL',?,?,?,0,0,0,0,?,?)",
                    UUID.randomUUID().toString(),state,destination.toString(),state.equals("CANCELLED")?null:JSON.writeValueAsString(content),OutboundSecurity.hash(content),state,
                    state.equals("CANCELLED")?OutboundSecurity.identityHash(content):null,state.equals("CANCELLED")?1L:null);
        }
    }
    /** 回填按 UTF-8 计费，旧写法被约束拒绝，未知正文不会被清理释放。 */
    public static void verifyMigrationAndCleanup(JdbcTemplate jdbc) throws Exception {
        var capacity=new OutboundCapacityRepository(jdbc,new OutboundCapacityProperties(1,1,1,1));
        var repository=new OutboundRepository(jdbc,JSON,capacity);
        Content content=new Content("Fixture","Frozen \uD83D\uDE80",null);
        long bytes=OutboundCapacityRepository.payloadBytes(JSON.writeValueAsString(content));
        assertThat(jdbc.queryForObject("SELECT payload_bytes FROM messaging_outbound_capacity",Long.class)).isEqualTo(bytes*2);
        assertThat(jdbc.queryForObject("SELECT delivery_rows FROM messaging_outbound_capacity",Long.class)).isEqualTo(3);
        assertThat(repository.byKey(77,"UNKNOWN").orElseThrow().content()).isEqualTo(content);
        assertThatThrownBy(()->jdbc.update("INSERT INTO messaging_destination(id,owner_id,binding_key,channel,label,secret_cipher,verification_reference,enabled,created_ms,updated_ms) VALUES(?,77,'old-writer','EMAIL','Inbox','cipher','fixture-only',TRUE,0,0)",UUID.randomUUID().toString())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE messaging_outbound SET generation=101 WHERE idempotency_key='UNKNOWN'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var retention=new OutboundRetentionService(jdbc,JSON,new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())),new OutboundRetentionProperties(true,1,1,10),capacity);
        assertThat(retention.sweep(NOW).get("bodies")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payload_bytes FROM messaging_outbound_capacity",Long.class)).isEqualTo(bytes);
        assertThat(repository.byKey(77,"FAILED").orElseThrow().content()).isNull();
        assertThat(repository.byKey(77,"FAILED").orElseThrow().contentIdentityHash()).isEqualTo(OutboundSecurity.identityHash(content));
        assertThat(repository.byKey(77,"UNKNOWN").orElseThrow().content()).isEqualTo(content);
        assertThat(capacity.status()).containsEntry("accountingMatches",true);
    }
    /** 多连接争抢最后一个投递额度，只接受一个身份，同键并发重放不扣新额度。 */
    public static void concurrentAdmission(JdbcTemplate jdbc) throws Exception {
        Fixture fixture=new Fixture(jdbc);
        List<Create> requests=new ArrayList<>();for(int i=0;i<8;i++)requests.add(fixture.request(UUID.randomUUID().toString()));
        List<UUID> results=parallel(requests,request->{try{return fixture.insert(request,false);}catch(OutboundException e){assertThat(e.code()).isEqualTo(ErrorCode.OUTBOUND_CAPACITY_EXCEEDED);return null;}});
        assertThat(results.stream().filter(Objects::nonNull).count()).isEqualTo(1);
        Create original=requests.get(results.indexOf(results.stream().filter(Objects::nonNull).findFirst().orElseThrow()));
        assertThat(parallel(Collections.nCopies(8,original),request->fixture.insert(request,false)).stream().distinct()).hasSize(1);
        assertThat(fixture.capacity.status()).containsEntry("accountingMatches",true);
        assertThat(jdbc.queryForObject("SELECT delivery_rows FROM messaging_outbound_capacity",Long.class)).isEqualTo(1);
    }
    /** 取消与创建并发共享一个身份，取消保留额不会被普通投递耗尽。 */
    public static void cancellationRace(JdbcTemplate jdbc) throws Exception {
        Fixture fixture=new Fixture(jdbc);Create request=fixture.request("same-key");
        java.util.concurrent.atomic.AtomicInteger ordinal=new java.util.concurrent.atomic.AtomicInteger();
        List<UUID> ids=parallel(Collections.nCopies(8,request),value->fixture.insert(value,ordinal.getAndIncrement()%2==0));
        assertThat(ids.stream().distinct()).hasSize(1);
        Delivery result=fixture.repository.byKey(77,"same-key").orElseThrow();
        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT delivery_rows+cancel_only_rows FROM messaging_outbound_capacity",Long.class)).isEqualTo(1);
        String kind=jdbc.queryForObject("SELECT capacity_kind FROM messaging_outbound",String.class);
        if("DELIVERY".equals(kind))fixture.insert(fixture.request("reserve-cancel"),true);
        else fixture.insert(fixture.request("normal-message"),false);
        assertThat(jdbc.queryForObject("SELECT delivery_rows+cancel_only_rows FROM messaging_outbound_capacity",Long.class)).isEqualTo(2);
        assertThatThrownBy(()->fixture.insert(fixture.request("excess-cancel"),true)).isInstanceOf(OutboundException.class).extracting("code").isEqualTo(ErrorCode.OUTBOUND_CAPACITY_EXCEEDED);
        assertThat(fixture.capacity.status()).containsEntry("accountingMatches",true);
    }
    /** V21 存量正文仍使用原保留策略，升级不能给未知消息凭空追加期限。 */
    public static void seedDeadlineLegacy(JdbcTemplate jdbc) throws Exception {
        UUID destination=UUID.randomUUID();Content content=new Content("Legacy","Frozen body",null);String body=JSON.writeValueAsString(content);
        jdbc.update("INSERT INTO messaging_destination(id,owner_id,binding_key,channel,label,secret_cipher,verification_reference,enabled,created_ms,updated_ms,capacity_recorded) VALUES(?,77,'legacy','EMAIL','Inbox','cipher','fixture',TRUE,0,0,TRUE)",destination.toString());
        jdbc.update("INSERT INTO messaging_outbound(id,owner_id,idempotency_key,destination_id,channel,content_json,payload_hash,status,expires_ms,next_attempt_ms,created_ms,updated_ms,capacity_kind,capacity_bytes) VALUES(?,77,'legacy',?,'EMAIL',?,?,'UNKNOWN',0,0,0,0,'DELIVERY',?)",UUID.randomUUID().toString(),destination.toString(),body,OutboundSecurity.hash(content),OutboundCapacityRepository.payloadBytes(body));
        jdbc.update("UPDATE messaging_outbound_capacity SET destination_rows=1,delivery_rows=1,payload_bytes=?",OutboundCapacityRepository.payloadBytes(body));
    }
    /** 旧消息期限保持空值，硬清理不会改变未知正文和原始幂等身份。 */
    public static void verifyDeadlineUpgrade(JdbcTemplate jdbc) {
        var capacity=new OutboundCapacityRepository(jdbc,new OutboundCapacityProperties(1,1,1,1024));
        var repository=new OutboundRepository(jdbc,JSON,capacity);Delivery old=repository.byKey(77,"legacy").orElseThrow();
        assertThat(old.contentExpiresAt()).isNull();assertThat(old.contentIdentityHash()).isNull();assertThat(old.status()).isEqualTo("UNKNOWN");
        assertThat(deadlineRetention(jdbc,capacity,10).sweepDeadlines(NOW)).containsEntry("deadlineBodies",0);
        assertThat(repository.byKey(77,"legacy").orElseThrow().content()).isEqualTo(new Content("Legacy","Frozen body",null));
        assertThat(capacity.status()).containsEntry("accountingMatches",true);
        assertThatThrownBy(()->jdbc.update("UPDATE messaging_outbound SET content_until_ms=0")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    /** 明确截止清除在途和未知副本，保留尝试审计并允许迟到提供方结果写回。 */
    public static void deadlineCleanupKeepsReconciliation(JdbcTemplate jdbc) {
        var capacity=new OutboundCapacityRepository(jdbc,new OutboundCapacityProperties(1,3,1,4096));
        var repository=new OutboundRepository(jdbc,JSON,capacity);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        UUID destination=UUID.randomUUID();Map<String,UUID> ids=new HashMap<>(),attempts=new HashMap<>();
        tx.executeWithoutResult(status->{
            capacity.lock();repository.bind(destination,new Binding(77,"binding",Channel.EMAIL,"Inbox","fixture@example.test","","fixture",true),"cipher",NOW);
            for(String state:List.of("ACCEPTED","SENDING","UNKNOWN")) {
                UUID id=UUID.randomUUID();ids.put(state,id);Content body=new Content("Fixture",state,null);
                repository.insert(id,new Create(77,state,destination,body,OutboundSecurity.hash(body),NOW.plusSeconds(3600),NOW.minusSeconds(1)),Channel.EMAIL,NOW.minusSeconds(120));
                if(!state.equals("ACCEPTED")) {
                    UUID attempt=repository.begin(repository.get(id,true).orElseThrow(),UUID.randomUUID(),1,NOW.minusSeconds(60));attempts.put(state,attempt);
                    if(state.equals("UNKNOWN"))repository.finish(id,attempt,"UNKNOWN",null,"MESSAGING_023",NOW.minusSeconds(30));
                }
            }
        });
        jdbc.update("UPDATE messaging_outbound SET relay_token=?,relay_until_ms=?",UUID.randomUUID().toString(),NOW.plusSeconds(120).toEpochMilli());
        jdbc.update("UPDATE messaging_outbound_retention_state SET last_status='FAILED'");
        var retention=deadlineRetention(jdbc,capacity,2);
        assertThat(retention.sweepDeadlines(NOW)).containsEntry("deadlineBodies",2);
        assertThat(retention.sweepDeadlines(NOW)).containsEntry("deadlineBodies",1);
        assertThat(retention.sweepDeadlines(NOW)).containsEntry("deadlineBodies",0);
        for(String state:ids.keySet()){
            Delivery d=repository.get(ids.get(state),false).orElseThrow();assertThat(d.content()).isNull();assertThat(d.contentIdentityHash()).isNotNull();
            assertThat(d.status()).isEqualTo(state.equals("ACCEPTED")?"EXPIRED":state);
        }
        assertThat(jdbc.queryForObject("SELECT payload_bytes FROM messaging_outbound_capacity",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM messaging_outbound_attempt",Long.class)).isEqualTo(2);
        tx.executeWithoutResult(status->{repository.finish(ids.get("SENDING"),attempts.get("SENDING"),"PROVIDER_ACCEPTED","fixture",null,NOW);repository.resolve(repository.get(ids.get("UNKNOWN"),true).orElseThrow(),new Resolution("NOT_SENT","manual-fixture"),NOW);});
        assertThat(repository.get(ids.get("SENDING"),false).orElseThrow().status()).isEqualTo("PROVIDER_ACCEPTED");
        assertThat(repository.get(ids.get("UNKNOWN"),false).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_status FROM messaging_outbound_retention_state",String.class)).isEqualTo("FAILED");
        assertThat(capacity.status()).containsEntry("accountingMatches",true);
    }
    /** 截止清理账本不一致时全部回滚，修复测试夹具后可按原身份再次清理。 */
    public static void deadlineRollbackAndLease(JdbcTemplate jdbc) {
        Fixture fixture=new Fixture(jdbc);Create original=fixture.request("deadline");
        Create request=new Create(original.ownerId(),original.idempotencyKey(),original.destinationId(),original.content(),original.payloadHash(),original.expiresAt(),NOW.minusSeconds(1));
        UUID id=fixture.insert(request,false);var retention=deadlineRetention(jdbc,fixture.capacity,10);
        jdbc.update("UPDATE messaging_outbound_deadline_state SET lease_token=?,lease_until_ms=?",UUID.randomUUID().toString(),NOW.plusSeconds(120).toEpochMilli());
        assertThat(retention.sweepDeadlines(NOW)).isEmpty();
        jdbc.update("UPDATE messaging_outbound_deadline_state SET lease_token=NULL,lease_until_ms=NULL");
        long bytes=jdbc.queryForObject("SELECT payload_bytes FROM messaging_outbound_capacity",Long.class);
        jdbc.update("UPDATE messaging_outbound_capacity SET payload_bytes=0");
        assertThatThrownBy(()->retention.sweepDeadlines(NOW)).isInstanceOf(OutboundException.class);
        assertThat(fixture.repository.get(id,false).orElseThrow().content()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT last_status FROM messaging_outbound_deadline_state",String.class)).isEqualTo("FAILED");
        jdbc.update("UPDATE messaging_outbound_capacity SET payload_bytes=?",bytes);
        assertThat(retention.sweepDeadlines(NOW)).containsEntry("deadlineBodies",1);
        assertThat(fixture.capacity.status()).containsEntry("accountingMatches",true);
    }
    private static OutboundRetentionService deadlineRetention(JdbcTemplate jdbc,OutboundCapacityRepository capacity,int batch) {
        return new OutboundRetentionService(jdbc,JSON,new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())),new OutboundRetentionProperties(false,90,90,batch),capacity);
    }
    private static List<UUID> parallel(List<Create> requests,java.util.function.Function<Create,UUID> action) throws Exception {
        CountDownLatch start=new CountDownLatch(1);List<Future<UUID>> futures=new ArrayList<>();
        try(ExecutorService pool=Executors.newFixedThreadPool(8)) {
            for(Create request:requests)futures.add(pool.submit(()->{assertThat(start.await(10,TimeUnit.SECONDS)).isTrue();return action.apply(request);}));
            start.countDown();List<UUID> results=new ArrayList<>();for(var future:futures)results.add(future.get(30,TimeUnit.SECONDS));return results;
        }
    }
    private static final class Fixture {
        final OutboundCapacityRepository capacity;
        final OutboundRepository repository;
        final TransactionTemplate tx;
        final UUID destination=UUID.randomUUID();
        Fixture(JdbcTemplate jdbc) {
            capacity=new OutboundCapacityRepository(jdbc,new OutboundCapacityProperties(1,1,1,1024));repository=new OutboundRepository(jdbc,JSON,capacity);
            tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
            tx.executeWithoutResult(status->{capacity.lock();repository.bind(destination,new Binding(77,"binding",Channel.EMAIL,"Inbox","fixture@example.test","","fixture-only",true),"fixture-cipher",NOW);});
        }
        Create request(String key){Content content=new Content("Subject","Frozen \uD83D\uDE80",null);return new Create(77,key,destination,content,OutboundSecurity.hash(content),NOW.plusSeconds(3600));}
        UUID insert(Create request,boolean cancel) {
            return tx.execute(status->{
                capacity.lock();repository.destination(77,destination,true).orElseThrow();
                var existing=repository.byKey(77,request.idempotencyKey(),true);
                if(existing.isPresent()) {if(cancel && existing.get().status().equals("ACCEPTED"))repository.state(existing.get().id(),"CANCELLED",NOW);return existing.get().id();}
                UUID id=UUID.randomUUID();if(cancel)repository.insertCancellation(id,request,Channel.EMAIL,NOW);else repository.insert(id,request,Channel.EMAIL,NOW);return id;
            });
        }
    }
}
