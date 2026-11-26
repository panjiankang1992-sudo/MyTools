package com.yuyutian.mytools.messaging.service;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;

class OutboundCapacityMigrationTest {
    @Test void populatedUpgradeAndCleanupKeepExactAccounting() throws Exception {
        JdbcDataSource data=data();Flyway.configure().dataSource(data).target("20").load().migrate();JdbcTemplate jdbc=new JdbcTemplate(data);
        OutboundCapacityDatabaseScenarios.seedLegacy(jdbc);Flyway.configure().dataSource(data).load().migrate();OutboundCapacityDatabaseScenarios.verifyMigrationAndCleanup(jdbc);
    }
    @Test void concurrentAdmissionAndReplayUseOneSlot() throws Exception {
        JdbcDataSource data=data();Flyway.configure().dataSource(data).load().migrate();OutboundCapacityDatabaseScenarios.concurrentAdmission(new JdbcTemplate(data));
    }
    @Test void cancellationRacingCreationPreservesOneTerminalIdentity() throws Exception {
        JdbcDataSource data=data();Flyway.configure().dataSource(data).load().migrate();OutboundCapacityDatabaseScenarios.cancellationRace(new JdbcTemplate(data));
    }
    @Test void deadlineUpgradePreservesLegacyPolicy() throws Exception {
        JdbcDataSource data=data();Flyway.configure().dataSource(data).target("21").load().migrate();JdbcTemplate jdbc=new JdbcTemplate(data);
        OutboundCapacityDatabaseScenarios.seedDeadlineLegacy(jdbc);Flyway.configure().dataSource(data).load().migrate();OutboundCapacityDatabaseScenarios.verifyDeadlineUpgrade(jdbc);
    }
    @Test void deadlineCleanupPreservesInFlightAudit() {
        JdbcDataSource data=data();Flyway.configure().dataSource(data).load().migrate();OutboundCapacityDatabaseScenarios.deadlineCleanupKeepsReconciliation(new JdbcTemplate(data));
    }
    @Test void deadlineCleanupRollbackAndLeaseRemainIndependent() {
        JdbcDataSource data=data();Flyway.configure().dataSource(data).load().migrate();OutboundCapacityDatabaseScenarios.deadlineRollbackAndLease(new JdbcTemplate(data));
    }
    private JdbcDataSource data(){JdbcDataSource data=new JdbcDataSource();data.setURL("jdbc:h2:mem:outbound-migration-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");return data;}
}
