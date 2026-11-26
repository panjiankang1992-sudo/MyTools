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
 * 显式配置公开取值时，派发必须使用独立服务身份与任务定义，
 * 从而与私有实例的任务域分离。本用例只验证参数构造，不代表公开隔离已具备。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pdfdispatchpublic;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "pdf.enabled=true",
        "pdf.text-enabled=true",
        "pdf.reconcile-ms=999999",
        "pdf.cleanup-ms=999999",
        "pdf.scheduler-token=test-scheduler-token",
        "pdf.scheduler-service-id=public-pdf-service",
        "pdf.task-name=toolbox_pdf_process",
        "pdf.business-type=PUBLIC_PDF_TOOL",
        "pdf.idempotency-prefix=public-pdf:"
})
class PublicPdfDispatchConfigTest {

    static final SchedulerStub STUB;
    static final Path ROOT;

    static {
        try {
            STUB = new SchedulerStub();
            ROOT = Files.createTempDirectory("pdf-dispatch-public-");
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
    @DisplayName("configured public values replace the private identifiers without touching other parameters")
    void publicProfileUsesSeparateIdentifiers() throws Exception {
        ObjectNode body = json.createObjectNode().put("name", "sample.pdf").put("bytes", 4).put("mediaType", "application/pdf");
        String id = service.upload(1, body, "dispatch-public-key").path("id").asText();
        service.content(1, id, 0, new ByteArrayInputStream(new byte[] {1, 2, 3, 4}));
        service.complete(1, id);

        service.reconcile();

        assertEquals(1, STUB.createBodies.size());
        assertEquals("public-pdf-service", STUB.serviceIds.get(0));
        String create = STUB.createBodies.get(0);
        assertTrue(create.contains("\"taskName\":\"toolbox_pdf_process\""), create);
        assertTrue(create.contains("\"businessType\":\"PUBLIC_PDF_TOOL\""), create);
        assertTrue(create.contains("\"idempotencyKey\":\"public-pdf:" + id + "\""), create);
        // 其余参数保持原样，公开 profile 不改变私有语义。
        assertTrue(create.contains("\"businessId\":\"" + id + "\""), create);
        assertTrue(create.contains("\"priority\":40"), create);
    }
}
