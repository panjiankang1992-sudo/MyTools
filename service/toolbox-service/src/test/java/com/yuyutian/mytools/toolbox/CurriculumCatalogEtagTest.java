package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.toolbox.config.ToolboxProperties;
import com.yuyutian.mytools.toolbox.controller.curriculum.CurriculumController;
import com.yuyutian.mytools.toolbox.model.TextbookCatalog;
import com.yuyutian.mytools.toolbox.model.TextbookEdition;
import com.yuyutian.mytools.toolbox.service.curriculum.CurriculumCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 回归目录业务版本号重复时，浏览器不能继续使用已下线的教材版本。 */
class CurriculumCatalogEtagTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CurriculumCatalog catalog = mock(CurriculumCatalog.class);
    private final CurriculumController controller = new CurriculumController(catalog, mock(ToolboxProperties.class), mapper);

    private TextbookCatalog view(String version) throws Exception {
        TextbookEdition edition = mapper.readValue("""
                {"editionId":"en-yl-4-1-yilin-2024","subject":"english","grade":4,
                 "semester":"first","contentVersion":"%s","reviewStatus":"published"}
                """.formatted(version), TextbookEdition.class);
        return new TextbookCatalog("catalog-6", List.of(edition), List.of());
    }

    @Test
    void reusedCatalogVersionStillInvalidatesOldContent() throws Exception {
        when(catalog.isReady()).thenReturn(true);
        when(catalog.catalog(any())).thenReturn(view("1.0.4"));
        var before = controller.list("english", null, null, null, null);
        String oldTag = before.getHeaders().getETag();
        assertThat(controller.list("english", null, null, null, oldTag).getStatusCode().value()).isEqualTo(304);
        // 模拟数据库恢复后沿用目录编号，但实际教材已更新。
        when(catalog.catalog(any())).thenReturn(view("1.0.5"));
        var after = controller.list("english", null, null, null, oldTag);
        assertThat(after.getStatusCode().value()).isEqualTo(200);
        assertThat(after.getHeaders().getETag()).isNotEqualTo(oldTag);
        assertThat(after.getBody().data().editions().get(0).contentVersion()).isEqualTo("1.0.5");
        assertThat(after.getHeaders().getCacheControl()).contains("no-cache");
        assertThat(controller.list("english", null, null, null, "\"catalog-6\"").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void etagMatchesFilteredResponseAndSupportsWeakValidators() throws Exception {
        when(catalog.isReady()).thenReturn(true);
        when(catalog.catalog(any())).thenReturn(view("1.0.5"));
        String tag = controller.list("english", null, null, null, null).getHeaders().getETag();
        assertThat(controller.list("english", null, null, null, "\"other\", W/" + tag).getStatusCode().value()).isEqualTo(304);
        assertThat(controller.list("english", null, null, null, "*").getStatusCode().value()).isEqualTo(304);
        assertThat(controller.list("english", null, null, null, "prefix" + tag).getStatusCode().value()).isEqualTo(200);
        // 不同筛选结果不能共用整个目录的业务版本号作为校验值。
        when(catalog.catalog(any())).thenReturn(new TextbookCatalog("catalog-6", List.of(), List.of()));
        assertThat(controller.list("chinese", null, null, null, tag).getStatusCode().value()).isEqualTo(200);
    }
}
