package com.yuyutian.mytools.task.scheduler.controller;

import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.config.InternalTokenFilter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class ImageDeploymentControllerTest {
    @Test void onlyOperatorCanEnableAndRepeatedRequestDoesNotBumpVersion() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:imageDeploy;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE execution_cluster(id VARCHAR PRIMARY KEY,name VARCHAR,enabled BOOLEAN,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE task_definition(id VARCHAR PRIMARY KEY,name VARCHAR,enabled BOOLEAN,version INT,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE image_deployment_audit(id VARCHAR PRIMARY KEY,enabled BOOLEAN,service_id VARCHAR,created_at TIMESTAMP)");
        jdbc.update("INSERT INTO execution_cluster(id,name,enabled) VALUES(?,?,false)", "a13bb181-55e4-41d5-ab17-68ed4626123f", "image-generation");
        jdbc.update("INSERT INTO task_definition(id,name,enabled,version) VALUES(?,?,false,1)", "bc4024f1-2062-440f-aed1-a50b9b6f6650", "image_generate");
        var controller = new ImageDeploymentController(jdbc);
        var request = new MockHttpServletRequest();
        var enable = new ImageDeploymentController.Deployment(true, "image-release-test");
        assertThrows(SchedulerException.class, () -> controller.update(enable, request));
        request.setAttribute(InternalTokenFilter.AUTHENTICATED_SERVICE_ATTRIBUTE, "image-generation-service");
        assertThrows(SchedulerException.class, () -> controller.update(enable, request));
        request.setAttribute(InternalTokenFilter.AUTHENTICATED_SERVICE_ATTRIBUTE, "task-operator-service");
        controller.update(enable, request);
        controller.update(enable, request);
        assertEquals(2, jdbc.queryForObject("SELECT version FROM task_definition", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT enabled FROM execution_cluster", Boolean.class));
        controller.update(new ImageDeploymentController.Deployment(false, "image-rollback-test"), request);
        assertFalse(jdbc.queryForObject("SELECT enabled FROM task_definition", Boolean.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM image_deployment_audit", Integer.class));
    }
}
