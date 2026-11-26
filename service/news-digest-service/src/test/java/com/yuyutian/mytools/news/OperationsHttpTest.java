package com.yuyutian.mytools.news;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:news-operations-http;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
        "news.internal-token=proxy-test","news.admin-token=admin-test"})
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@AutoConfigureMockMvc
class OperationsHttpTest {
    @Autowired MockMvc mvc;
    @Test void metricsArePrivateAndContainOnlyFixedNamesAndApplicationLabel() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").header("Authorization","Bearer proxy-test")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").header("Authorization","Bearer admin-test"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("news_operations_delivery_unknown")))
                .andExpect(content().string(containsString("application=\"news-digest-service\"")))
                .andExpect(content().string(not(containsString("ownerId="))));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.components").doesNotExist());
    }
    @Test void disabledRetentionAllowsPreviewButRefusesMutationAndValidatesSourcePolicy() throws Exception {
        mvc.perform(get("/internal/admin/retention/preview").header("Authorization","Bearer admin-test"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.articleEvidence").value(0));
        mvc.perform(post("/internal/admin/retention/sweep").header("Authorization","Bearer admin-test"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("NEWS_027"));
        mvc.perform(put("/internal/admin/sources/missing/retention").header("Authorization","Bearer admin-test")
                        .contentType("application/json").content("{\"evidenceHours\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NEWS_002"));
    }
}
