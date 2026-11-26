package com.yuyutian.mytools.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.EvidenceModels.*;
import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.common.*;
import com.yuyutian.mytools.news.config.*;
import com.yuyutian.mytools.news.controller.*;
import com.yuyutian.mytools.news.mapper.*;
import com.yuyutian.mytools.news.service.*;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EvidenceTest {
    private final Instant now=Fixtures.CUTOFF;
    private final RetentionProperties policy=new RetentionProperties(true,168,90,180,90,250);
    private JdbcTemplate jdbc;
    private ObjectMapper json;
    private NewsRepository news;
    private EvidenceRepository evidence;
    private RetentionRepository retention;
    private Edition edition;
    private Article article;
    @BeforeEach void setup() {
        JdbcDataSource data=new JdbcDataSource();data.setURL("jdbc:h2:mem:evidence-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        Flyway.configure().dataSource(data).load().migrate();jdbc=new JdbcTemplate(data);json=new ObjectMapper().findAndRegisterModules();
        TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(data));
        news=new NewsRepository(jdbc,json,tx,new GithubSnapshotRepository(jdbc,json, Fixtures.capacity(jdbc), Fixtures.transaction(jdbc)), Fixtures.capacity(jdbc), Fixtures.control(jdbc), Fixtures.registry(jdbc));evidence=new EvidenceRepository(jdbc,json);retention=new RetentionRepository(jdbc,json,tx,policy, Fixtures.capacity(jdbc));
        news.saveSource(Fixtures.source("source",Category.TECH));
        news.ingest("source",List.of(new ArticleInput("https://example.com/story","Reported original event",now.minusSeconds(3600),Fixtures.EVIDENCE,0)),now.minusSeconds(3600));
        article=news.candidates(now).getFirst();
        edition=new DigestEngine().build(UUID.randomUUID(),7,now,now,ZoneOffset.UTC,RegionSnapshot.unresolved(),List.of(article));
        news.saveEdition(edition,"evidence-key","hash");
    }
    @Test void readsCurrentStateWithoutReturningTextOrMutatingFrozenEdition() throws Exception {
        Snapshot status=service(now).inspect(7,edition.id());
        assertThat(status.editionId()).isEqualTo(edition.id());assertThat(status.checkedAt()).isEqualTo(now);
        assertThat(status.items()).containsExactly(new Item(article.id(),Status.AVAILABLE,article.fetchedAt().plus(Duration.ofDays(7))));
        assertThat(json.writeValueAsString(status)).doesNotContain("evidenceText","Reported original event","verified source paragraph");
        assertThat(news.edition(7,edition.id())).isEqualTo(edition);
    }
    @Test void deadlineIsExpiredBeforeCleanupAndShorterSourcePolicyAppliesImmediately() {
        retention.sourcePolicy("source",2,now);
        assertThat(service(now.plusSeconds(3599)).inspect(7,edition.id()).items().getFirst().status()).isEqualTo(Status.AVAILABLE);
        Snapshot after=service(now.plusSeconds(3600)).inspect(7,edition.id());
        assertThat(after.items().getFirst()).isEqualTo(new Item(article.id(),Status.RETENTION_EXPIRED,now.plusSeconds(3600)));
        assertThat(news.candidates(now).getFirst().evidenceText()).isNotEmpty();
        assertThat(news.edition(7,edition.id())).isEqualTo(edition);
    }
    @Test void removedEvidenceCannotBecomeAvailableWhenPolicyIsExtended() {
        retention.sourcePolicy("source",1,now);retention.sweep(now.plusSeconds(1));
        retention.sourcePolicy("source",168,now.plusSeconds(2));
        assertThat(service(now.plusSeconds(2)).inspect(7,edition.id()).items().getFirst().status()).isEqualTo(Status.RETENTION_EXPIRED);
        assertThat(news.edition(7,edition.id())).isEqualTo(edition);
    }
    @Test void missingMetadataAndWrongVersionAreUnavailableWithoutGuessedExpiry() throws Exception {
        var tree=json.valueToTree(article);((com.fasterxml.jackson.databind.node.ObjectNode)tree).put("contentHash","wrong");
        jdbc.update("UPDATE news_article SET payload_json=? WHERE id=?",json.writeValueAsString(tree),article.id());
        assertThat(service(now).inspect(7,edition.id()).items()).containsExactly(new Item(article.id(),Status.UNAVAILABLE,null));
        jdbc.update("DELETE FROM news_article WHERE id=?",article.id());
        assertThat(service(now).inspect(7,edition.id()).items()).containsExactly(new Item(article.id(),Status.UNAVAILABLE,null));
    }
    @Test void crossOwnerAndExpiredEditionCannotRevealEvidence() {
        assertCode(()->service(now).inspect(8,edition.id()),ErrorCode.NEWS_003);
        jdbc.update("UPDATE news_edition SET payload_json=NULL,content_expired_ms=? WHERE id=?",now.toEpochMilli(),edition.id().toString());
        assertCode(()->service(now).inspect(8,edition.id()),ErrorCode.NEWS_003);
        assertCode(()->service(now).inspect(7,edition.id()),ErrorCode.NEWS_026);
    }
    @Test void emptyEditionDoesNotExposeOtherCollectedArticles() {
        Edition empty=new DigestEngine().build(UUID.randomUUID(),7,now,now,ZoneOffset.UTC,RegionSnapshot.unresolved(),List.of());
        news.saveEdition(empty,"empty","hash");assertThat(service(now).inspect(7,empty.id()).items()).isEmpty();
    }
    @Test void hnDiscussionAndExternalPageEvidenceBothRemainAvailable() {
        for (boolean external : List.of(false, true)) {
            String key = external ? "hn-external" : "hn-discussion";
            SourceSpec source = new SourceSpec(key, key, "https://hacker-news.firebaseio.com/v0/topstories.json", Category.TECH,
                    "hacker-news", null, EvidencePolicy.EXCERPT_ALLOWED, "test-fixture", 0.8, true, SourceKind.HACKER_NEWS);
            news.saveSource(source);
            String page = "https://example.org/article";
            String discussion = "https://news.ycombinator.com/item?id=1";
            var item = new CollectedArticle(new ArticleInput(page, "Reported HN event", now.minusSeconds(60), Fixtures.EVIDENCE, 10),
                    new TechnicalMetadata("1", discussion, now.minusSeconds(60), 10, 2, null, external ? page : null));
            assertThat(news.acquireCollection(key, key, now.minusSeconds(30))).isTrue();
            news.finishTechnicalCollection(key, key, now.minusSeconds(20), new CollectedBatch(List.of(item), List.of(),
                    new CollectionCoverage(1, 1, 1, 0, 0, List.of())), "FRESH", now.minusSeconds(60));
            Article stored = news.candidates(now).stream().filter(value -> value.source().id().equals(key)).findFirst().orElseThrow();
            Edition digest = new DigestEngine().build(UUID.randomUUID(), 7, now, now, ZoneOffset.UTC, RegionSnapshot.unresolved(), List.of(stored));
            news.saveEdition(digest, key, key);
            assertThat(digest.sections().get(Category.TECH).items().getFirst().detail().citations().getFirst().url())
                    .isEqualTo(external ? page : discussion);
            assertThat(service(now).inspect(7, digest.id()).items().getFirst().status()).isEqualTo(Status.AVAILABLE);
        }
    }
    @Test void endpointRequiresProxyTokenAndKeepsOwnerCheckAndNoStore() throws Exception {
        var filter=new InternalTokenFilter(new NewsProperties("proxy","admin",false,ZoneOffset.UTC,LocalTime.NOON));
        var mvc=MockMvcBuilders.standaloneSetup(new NewsEvidenceController(service(now))).addFilters(filter).setControllerAdvice(new NewsExceptionHandler()).build();
        String path="/internal/v1/news/editions/"+edition.id()+"/evidence";
        mvc.perform(get(path).param("ownerId","7")).andExpect(status().isUnauthorized());
        mvc.perform(get(path).param("ownerId","8").header("Authorization","Bearer proxy")).andExpect(status().isNotFound());
        mvc.perform(get(path).param("ownerId","7").param("articleId","forged").header("Authorization","Bearer proxy"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store, private"))
                .andExpect(jsonPath("items[0].articleId").value(article.id())).andExpect(jsonPath("items[0].status").value("AVAILABLE"));
    }
    private EvidenceService service(Instant time){return new EvidenceService(news,evidence,policy,Clock.fixed(time,ZoneOffset.UTC));}
    private static void assertCode(Runnable action,ErrorCode code){assertThatThrownBy(action::run).isInstanceOfSatisfying(NewsException.class,error->assertThat(error.code()).isEqualTo(code));}
}
