import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository;
import com.yuyutian.mytools.toolbox.model.CatalogManifest;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.AdminTextbookService;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.AdminIdempotencyService;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.DraftToBookMapper;
import com.yuyutian.mytools.toolbox.service.admin.migration.CurriculumFileImporter;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft;
import com.yuyutian.mytools.toolbox.service.admin.validation.CurriculumValidator;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 独立内存 H2 探针，不访问应用或生产数据库，不验证 Spring 事务代理。 */
public class CurriculumPersistenceProbe {
    /** 模拟回执存储故障，验证业务已经提交的客观状态。 */
    private static class FailingReceiptRepository extends AdminTextbookRepository {
        FailingReceiptRepository(JdbcTemplate jdbc) { super(jdbc); }
        @Override public void recordOperation(String actor, String scope, String key, String digest, String result) {
            throw new IllegalStateException("injected receipt failure");
        }
    }

    /** 仅写入随机内存 H2 实例，进程退出即清理。 */
    public static void main(String[] args) throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:acceptance-probe;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new FileSystemResource(
                "service/toolbox-service/src/main/resources/db/migration/V3__toolbox_admin.sql")).execute(dataSource);
        var jdbc = new JdbcTemplate(dataSource);
        var repository = new FailingReceiptRepository(jdbc);
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var service = new AdminTextbookService(repository, new CurriculumValidator(), null, new DraftToBookMapper(), objectMapper);
        Path root = Path.of("toolbox-web/content");
        var manifest = objectMapper.readValue(root.resolve("catalog/published.json").toFile(), CatalogManifest.class);
        var edition = manifest.editions().get(0);
        var bytes = Files.readAllBytes(root.resolve("textbooks/" + edition.editionId() + "/" + edition.contentVersion() + "/book.json"));
        new CurriculumFileImporter(repository).importEdition(edition.editionId(), edition.contentVersion(), bytes,
                edition.contentSha256(), objectMapper.writeValueAsString(edition), "audit");
        var draft = service.findDraft(edition.editionId()).orElseThrow();
        System.out.println("IMPORTED_RAW_BYTES_IDENTICAL=" + java.util.Arrays.equals(bytes,
                repository.findVersion(edition.editionId(), edition.contentVersion()).orElseThrow().contentBytes()));
        System.out.println("IMPORTED_DRAFT: subject=" + draft.draft().subject() + "; grade=" + draft.draft().grade()
                + "; entries=" + draft.draft().entries() + "; base=" + draft.baseContentVersion());
        System.out.println("IMPORTED_CATALOG: file=" + manifest.catalogVersion() + "; database=catalog-" + repository.currentEpoch());

        var blank = new AdminTextbookDraft("probe-new", "chinese", 1, "first", "publisher", "series", "edition",
                "source", "partial", List.of(), List.of(), List.of());
        long before = service.create(blank, "audit").revision();
        System.out.println("DRAFT_LIST: all=" + repository.countTextbooks(null, null, null, null, "draft")
                + "; subjectChinese=" + repository.countTextbooks(null, "chinese", null, null, "draft"));
        var invalid = new AdminTextbookDraft("probe-new", "chinese", 1, "first", "publisher", "series", "edition",
                "source", "partial", List.of(), List.of(), List.of(
                new AdminTextbookDraft.DraftEntry("row", null, "missing-unit", null, null, null,
                        "山", "shān", null, List.of("writing"), false)));
        long after = service.saveDraft("probe-new", before, invalid, "audit");
        System.out.println("INVALID_DRAFT_SAVED: revision=" + after + "; missingUnitPersisted="
                + service.findDraft("probe-new").orElseThrow().draft().entries().get(0).unitId());
        var idempotency = new AdminIdempotencyService(repository, objectMapper);
        try {
            idempotency.execute("audit", "draft.save", "same-key", invalid, Map.class,
                    () -> Map.of("revision", service.saveDraft("probe-new", after, invalid, "audit")));
        } catch (Exception expected) {
            System.out.println("RECEIPT_FAILURE_TYPE=" + expected.getClass().getSimpleName());
        }
        System.out.println("REVISION_AFTER_FAILED_RECEIPT=" + service.findDraft("probe-new").orElseThrow().revision());
        System.out.println("RECEIPT_EXISTS=" + repository.findOperation("audit", "draft.save", "same-key").isPresent());
        try {
            idempotency.execute("audit", "draft.save", "same-key", invalid, Map.class,
                    () -> Map.of("revision", service.saveDraft("probe-new", after, invalid, "audit")));
        } catch (Exception expected) {
            System.out.println("IDENTICAL_RETRY_ERROR=" + expected.getMessage());
        }
    }
}
