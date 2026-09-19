package com.yuyutian.mytools.task.scheduler.controller;

import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.config.InternalTokenFilter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class VideoDeploymentControllerTest {
    @Test void onlyOperatorCanEnableAndRepeatedRequestDoesNotBumpVersion() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:videoDeploy;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE execution_cluster(id VARCHAR PRIMARY KEY,name VARCHAR,enabled BOOLEAN,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE task_definition(id VARCHAR PRIMARY KEY,name VARCHAR,enabled BOOLEAN,version INT,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE video_deployment_audit(id VARCHAR PRIMARY KEY,enabled BOOLEAN,service_id VARCHAR,created_at TIMESTAMP)");
        jdbc.update("INSERT INTO execution_cluster(id,name,enabled) VALUES(?,?,false)", "48615d43-0768-4d04-aa73-78fc97f57e47", "video-generation");
        jdbc.update("INSERT INTO task_definition(id,name,enabled,version) VALUES(?,?,false,1)", "44544153-c55b-4124-8d7e-0365f70c3893", "video_generate");
        var controller = new VideoDeploymentController(jdbc);
        var request = new MockHttpServletRequest();
        var enable = new VideoDeploymentController.Deployment(true, "video-release-test");
        assertThrows(SchedulerException.class, () -> controller.update(enable, request));
        request.setAttribute(InternalTokenFilter.AUTHENTICATED_SERVICE_ATTRIBUTE, "video-generation-service");
        assertThrows(SchedulerException.class, () -> controller.update(enable, request));
        request.setAttribute(InternalTokenFilter.AUTHENTICATED_SERVICE_ATTRIBUTE, "task-operator-service");
        controller.update(enable, request);
        controller.update(enable, request);
        assertEquals(2, jdbc.queryForObject("SELECT version FROM task_definition", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT enabled FROM execution_cluster", Boolean.class));
        controller.update(new VideoDeploymentController.Deployment(false, "video-rollback-test"), request);
        assertFalse(jdbc.queryForObject("SELECT enabled FROM task_definition", Boolean.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM video_deployment_audit", Integer.class));
    }
}
