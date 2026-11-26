package com.yuyutian.mytools.pdf.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 未配置覆盖项时，派发必须与既有私有实例完全一致。
 * 这是公开实例改造的回归基线：任何默认值变化都会让本用例失败。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pdfdispatchdefault;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "pdf.enabled=true",
        "pdf.text-enabled=true",
        "pdf.reconcile-ms=999999",
        "pdf.cleanup-ms=999999",
        "pdf.scheduler-token=test-scheduler-token"
})
class PdfDispatchDefaultsTest {

    static final SchedulerStub STUB;
    static final Path ROOT;

    static {
        try {
            STUB = new SchedulerStub();
            ROOT = Files.createTempDirectory("pdf-dispatch-default-");
        } catch (Exception cause) {
            throw new ExceptionInInitializerError(cause);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pdf.scheduler-url", STUB::url);
        registry.add("pdf.root", ROOT::toString);
    }

    @Autowired
    PdfService service;

    @Autowired
    PdfStore store;

    @Autowired
    ObjectMapper json;

    @BeforeEach
    void reset() {
        store.jdbc().update("DELETE FROM pdf_record");
        STUB.reset();
    }

    @Test
    @DisplayName("default dispatch keeps the private service id, task name, business type and idempotency prefix")
    void defaultDispatchKeepsPrivateIdentifiers() throws Exception {
        ObjectNode body = json.createObjectNode().put("name", "sample.pdf").put("bytes", 4).put("mediaType", "application/pdf");
        String id = service.upload(1, body, "dispatch-default-key").path("id").asText();
        service.content(1, id, 0, new ByteArrayInputStream(new byte[] {1, 2, 3, 4}));
        service.complete(1, id);

        service.reconcile();

        assertEquals(1, STUB.createBodies.size(), "应恰好创建一次任务实例");
        assertEquals("pdf-service", STUB.serviceIds.get(0));
        String create = STUB.createBodies.get(0);
        assertTrue(create.contains("\"taskName\":\"pdf_process\""), create);
        assertTrue(create.contains("\"businessType\":\"PDF_TOOL\""), create);
        assertTrue(create.contains("\"idempotencyKey\":\"pdf:" + id + "\""), create);
        assertTrue(create.contains("\"parameters\":{\"workId\":\"" + id + "\"}"), create);
    }
}
