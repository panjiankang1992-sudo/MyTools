package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.toolbox.common.BusinessException;
import com.yuyutian.mytools.toolbox.common.ErrorCode;
import com.yuyutian.mytools.toolbox.config.CurriculumProperties;
import com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.AdminTextbookService;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * F11：**目录来源是只读文件（默认配置）时，管理端发布必须被明确拒绝**。
 *
 * 审查复现的静默失败是：`repository=files` 下发布写入数据库、公开目录仍读文件目录——
 * 管理员看到"发布成功"，用户刷新却什么都看不到，而且**没有任何错误**。
 * 修复要求是"files 模式禁止管理发布，或显式完整切库后才开放"，这里实现前者。
 *
 * 本类**不覆盖** `toolbox.curriculum.repository`，因此跑的就是生产默认值 `files`
 * （`CurriculumProperties` 的 `@DefaultValue("files")`）；用例开头会再断言一次这个前提，
 * 避免将来有人改了默认值而这条测试"因为别的原因"通过。
 *
 * 反向验证：把 `AdminTextbookService.publish` 与 `CurriculumPublisher` 里的
 * `requireWritableRepository()` 去掉，本类的发布用例必须失败。
 */
@SpringBootTest
@ActiveProfiles("test")
class FilesRepositoryPublishBlockedTest {

    /** 测试操作者标识。 */
    private static final String ACTOR = "gate-test-admin";

    /**
     * 独立内存库：本类只验证"拒绝"，既不依赖也不污染其它用例的数据。
     *
     * @param registry 动态属性注册器
     */
    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:files-publish-gate;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    private AdminTextbookService textbooks;

    @Autowired
    private AdminTextbookRepository repository;

    @Autowired
    private CurriculumProperties curriculumProperties;

    @Autowired
    private ObjectMapper objectMapper;

    /** 构造一份**最小可发布**的中文草稿 JSON（与公开接口用的是同一形状）。 */
    private static String draftJson(String editionId) {
        return """
                {"editionId":"%s","subject":"chinese","grade":1,"semester":"first",
                 "publisher":"人民教育出版社","series":"统编语文","editionLabel":"2024 年修订版",
                 "sourceNote":"纸质教材附录，第 78 页","coverageDeclaration":"partial",
                 "units":[{"unitId":null,"clientUnitId":"cu1","title":"第一单元","order":1}],
                 "lessons":[],"entries":[]}
                """.formatted(editionId);
    }

    /** 反序列化草稿。 */
    private AdminTextbookDraft draft(String editionId) throws Exception {
        return objectMapper.readValue(draftJson(editionId), AdminTextbookDraft.class);
    }

    @Test
    @DisplayName("F11：files 模式下发布被拒，错误码可区分且未写入任何版本")
    void publishIsRejectedWhenRepositoryIsFiles() throws Exception {
        // 前提：默认配置确实是 files（生产默认值，不是测试里特意打开的开关）。
        assertThat(curriculumProperties.usesDatabase())
                .as("本用例的前提是目录来源为默认的 files").isFalse();

        String editionId = "gate-files-book";
        AdminTextbookDraft draft = draft(editionId);
        AdminTextbookRepository.DraftRef created = textbooks.create(draft, ACTOR);

        // 草稿**本身是可发布的**：这样"被拒绝"才只能归因于来源模式，而不是校验不通过。
        assertThat(textbooks.validate(editionId, created.revision()).report().publishable())
                .as("草稿必须通过发布级校验，否则这条测试证明不了闸门存在").isTrue();

        assertThatThrownBy(() -> textbooks.publish(editionId, ACTOR, created.revision()))
                .as("files 模式下发布不能静默成功")
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    // **明确、可区分**的错误：不是"校验失败"，也不是笼统的 500。
                    assertThat(failure.errorCode()).isEqualTo(ErrorCode.TOOLBOX_ADMIN_011);
                    assertThat(failure.errorCode().httpStatus()).isEqualTo(409);
                    assertThat(failure.errorCode().messageKey()).isEqualTo("admin.publish.disabled");
                    assertThat(failure.details()).containsEntry("reason", "repositoryIsFiles");
                });

        // 被拒绝的发布**不能留下痕迹**：没有版本行、当前指针仍为空。
        assertThat(repository.listAllVersions(editionId)).as("拒绝后不应存在任何已发布版本").isEmpty();
        assertThat(repository.findCurrentVersionId(editionId)).as("拒绝后当前版本指针不能前移").isEmpty();
    }

    @Test
    @DisplayName("F11：files 模式下保存草稿仍然可用——闸门只挡发布")
    void saveDraftStillWorksWhenRepositoryIsFiles() throws Exception {
        String editionId = "gate-files-draft-book";
        AdminTextbookDraft draft = draft(editionId);
        AdminTextbookRepository.DraftRef created = textbooks.create(draft, ACTOR);

        long saved = textbooks.saveDraft(editionId, created.revision(), draft, ACTOR);

        // 编辑（含为"切库后发布"做准备）必须照常可用，否则切换来源前没法录数据。
        assertThat(saved).isEqualTo(created.revision() + 1);
        assertThat(textbooks.findDraft(editionId)).isPresent();
    }
}
