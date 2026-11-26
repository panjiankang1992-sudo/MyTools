package com.yuyutian.mytools.rss;

import com.yuyutian.mytools.feed.*;
import com.yuyutian.mytools.rss.Model.RssModels.*;
import com.yuyutian.mytools.rss.common.ErrorCode;
import com.yuyutian.mytools.rss.common.RssException;
import com.yuyutian.mytools.rss.mapper.*;
import com.yuyutian.mytools.rss.service.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=${RSS_TEST_DB_URL:jdbc:h2:mem:rss_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}", "spring.datasource.username=${RSS_TEST_DB_USERNAME:sa}", "spring.datasource.password=", "rss.polling-enabled=false", "rss.internal-token=offline-rss-integration-token-32-characters"})
@AutoConfigureMockMvc
class RssIntegrationTest {
    private static final long NOW = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli();
    private static final String URL = "https://example.com/feed";
    private static final Filter ALL = new Filter(null, null, "all", null);
    @Autowired SubscriptionRepository subscriptions;
    @Autowired EntryRepository entries;
    @Autowired PollRepository polls;
    @Autowired PollingService polling;
    @Autowired DiscoveryService discovery;
    @Autowired OpmlService opml;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean FeedTransport transport;
    @MockitoBean Clock clock;

    @Test void concurrentDevicesHaveOneStateWinner() throws Exception {
        create(1, false); collect();
        long id = Long.parseLong(entries.list(1, ALL, null, 30).items().getFirst().id());
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<String> change = () -> {
                start.await();
                try { entries.state(1, id, new StateChange(0L, true, true)); return "OK"; }
                catch (RssException exception) { return exception.code().name(); }
            };
            var first = executor.submit(change); var second = executor.submit(change); start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder("OK", "RSS_009");
        }
        assertThat(entries.get(1, id).revision()).isEqualTo(1);
    }

    @Test void concurrentQuotaAndClaimsStayBounded() throws Exception {
        for (int i = 0; i < 99; i++) subscriptions.create(1, new CreateSubscription(URL + i, "", null, false));
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.function.Function<String, String> create = suffix -> {
                try { start.await(); subscriptions.create(1, new CreateSubscription(URL + suffix, "", null, false)); return "OK"; }
                catch (RssException exception) { return exception.code().name(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
            };
            var first = executor.submit(() -> create.apply("a")); var second = executor.submit(() -> create.apply("b")); start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder("OK", "RSS_012");
            assertThat(subscriptions.list(1)).hasSize(100);
            var one = executor.submit(() -> polls.claim().orElseThrow());
            var two = executor.submit(() -> polls.claim().orElseThrow());
            assertThat(one.get().id()).isNotEqualTo(two.get().id());
        }
    }

    @Test void stateRequiresBothExplicitValuesAndRejectsOversizedBodies() throws Exception {
        mvc.perform(put("/internal/v1/rss/entries/1/state")
                .header("Authorization", "Bearer offline-rss-integration-token-32-characters").header("X-RSS-Owner", "1")
                .contentType("application/json").content("{\"expectedRevision\":0,\"starred\":true}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RSS_001"));
        mvc.perform(post("/internal/v1/rss/opml/preview").contentType("application/json").content("x".repeat(2 * 1024 * 1024 + 1)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("RSS_006"));
    }

    @BeforeEach void setup() {
        for (String table : List.of("rss_entry_state", "rss_entry", "rss_subscription", "rss_group", "rss_owner")) jdbc.update("DELETE FROM " + table);
        reset(transport, clock);
        when(clock.millis()).thenReturn(NOW);
        when(clock.instant()).thenReturn(Instant.ofEpochMilli(NOW));
        when(transport.fetch(anyString(), any(), any())).thenReturn(response(200, feed("a", "First"), "v1", null));
    }

    @Test void independentUsersAndDuplicateSubscriptionsKeepState() {
        var subscription = create(1, false);
        collect();
        assertThat(create(1, true).id()).isEqualTo(subscription.id());
        assertThat(entries.counts(1).unread()).isEqualTo(1);
        assertThat(subscriptions.list(2)).isEmpty();
        var entry = entries.list(1, ALL, null, 30).items().getFirst();
        assertThatThrownBy(() -> entries.get(2, Long.parseLong(entry.id()))).isInstanceOf(RssException.class);
        assertThatThrownBy(() -> subscriptions.remove(2, subscription.id())).isInstanceOf(RssException.class);
        assertThat(create(2, false).id()).isNotEqualTo(subscription.id());
    }

    @Test void repeatedAndRevisedItemsDoNotResetReadOrStar() {
        var subscription = create(1, false); collect();
        var item = entries.list(1, ALL, null, 30).items().getFirst();
        long id = Long.parseLong(item.id());
        entries.state(1, id, new StateChange(0L, true, true));
        when(transport.fetch(anyString(), any(), any())).thenReturn(response(200, feed("a", "Revised"), "v2", null));
        due(subscription.id()); collect();
        var revised = entries.get(1, id);
        assertThat(revised.title()).isEqualTo("Revised");
        assertThat(revised.read()).isTrue(); assertThat(revised.starred()).isTrue();
        assertThat(revised.revision()).isEqualTo(1); assertThat(revised.discoveredAt()).isEqualTo(NOW);
        assertThat(entries.list(1, ALL, null, 30).items()).hasSize(1);
    }

    @Test void firstImportHasFiftyUnreadAndRetryDoesNotChangePolicy() {
        StringBuilder xml = new StringBuilder("<rss version='2.0'><channel><title>Feed</title>");
        for (int i = 0; i < 60; i++) xml.append("<item><guid>").append(i).append("</guid><title>Item ").append(i).append("</title></item>");
        xml.append("</channel></rss>");
        when(transport.fetch(anyString(), any(), any())).thenReturn(response(200, xml.toString(), "v1", null));
        var subscription = create(1, false); collect();
        assertThat(entries.counts(1).unread()).isEqualTo(50);
        assertThat(entries.list(1, ALL, null, 100).items().getFirst().title()).isEqualTo("Item 0");
        due(subscription.id()); collect();
        assertThat(entries.counts(1).unread()).isEqualTo(50);
    }

    @Test void initialAllReadDoesNotMarkFutureArticlesRead() {
        var subscription = create(1, true); collect();
        assertThat(entries.counts(1).unread()).isZero();
        when(transport.fetch(anyString(), any(), any())).thenReturn(response(200, feed("b", "New"), "v2", null));
        due(subscription.id()); collect();
        assertThat(entries.counts(1).unread()).isEqualTo(1);
    }

    @Test void aggregateFeedMigrationImportsWholeWeekAndPreservesPreviousReadState() {
        var subscription = create(1, false); collect();
        var previous = entries.list(1, ALL, null, 30).items().getFirst();
        entries.state(1, Long.parseLong(previous.id()), new StateChange(0L, true, true));
        StringBuilder xml = new StringBuilder("<rss version='2.0' xmlns:content='http://purl.org/rss/1.0/modules/content/'><channel>");
        for (int i = 0; i < 560; i++) {
            xml.append("<item><guid>urn:news:").append(i).append("</guid><title>News ").append(i)
                    .append("</title><content:encoded><![CDATA[<p>Detailed facts</p><ul><li>Verified point</li></ul>]]></content:encoded></item>");
        }
        when(transport.fetch(anyString(), any(), any())).thenReturn(response(200, xml.append("</channel></rss>").toString(), "week", null));
        due(subscription.id()); collect();
        assertThat(subscriptions.get(1, subscription.id()).lastErrorCode()).isNull();
        assertThat(entries.counts(1).unread()).isEqualTo(560);
        int total = 0;
        String cursor = null;
        do {
            var page = entries.list(1, new Filter(null, null, "unread", null), cursor, 100);
            total += page.items().size();
            for (var item : page.items()) {
                assertThat(entries.get(1, Long.parseLong(item.id())).contentHtml()).contains("Detailed facts", "Verified point");
            }
            cursor = page.nextCursor();
        } while (!cursor.isBlank());
        assertThat(total).isEqualTo(560);
        assertThat(entries.get(1, Long.parseLong(previous.id())).read()).isTrue();
        assertThat(entries.get(1, Long.parseLong(previous.id())).starred()).isTrue();
        due(subscription.id()); collect();
        assertThat(entries.counts(1).unread()).isEqualTo(560);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rss_entry", Long.class)).isEqualTo(561);
    }

    @Test void conditionalResponsePreservesDataAndUpdatesSuccess() {
        var subscription = create(1, false); collect();
        when(transport.fetch(anyString(), any(), any())).thenReturn(response(304, "", null, null));
        when(clock.millis()).thenReturn(NOW + 3600_000);
        due(subscription.id()); collect();
        verify(transport).fetch(URL, "v1", null);
        assertThat(subscriptions.get(1, subscription.id()).lastSuccessAt()).isEqualTo(NOW + 3600_000);
        assertThat(entries.counts(1).unread()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT etag FROM rss_subscription WHERE id=?", String.class, subscription.id())).isEqualTo("v1");
    }

    @Test void invalidBodyNeverAdvancesEtagOrRemovesExistingData() {
        var subscription = create(1, false); collect();
        when(transport.fetch(anyString(), any(), any())).thenReturn(response(200, "<html>error</html>", "v2", null));
        due(subscription.id()); collect();
        assertThat(subscriptions.get(1, subscription.id()).lastErrorCode()).isEqualTo("RSS_004");
        assertThat(jdbc.queryForObject("SELECT etag FROM rss_subscription WHERE id=?", String.class, subscription.id())).isEqualTo("v1");
        assertThat(entries.counts(1).unread()).isEqualTo(1);
    }

    @Test void leaseExpiryPauseAndReplacementFenceOldResults() {
        var subscription = create(1, false);
        var old = polls.claim().orElseThrow();
        assertThat(polls.claim()).isEmpty();
        when(clock.millis()).thenReturn(NOW + 61_000);
        var replacement = polls.claim().orElseThrow();
        var parsed = new FeedParser().parse(feed("a", "First").getBytes(StandardCharsets.UTF_8), URL);
        assertThat(polls.success(old, response(200, "", "stale", null), parsed)).isFalse();
        subscriptions.update(1, subscription.id(), new UpdateSubscription(0L, "", null, "PAUSED", 30));
        assertThat(polls.success(replacement, response(200, "", "stale", null), parsed)).isFalse();
        assertThat(entries.counts(1).unread()).isZero();
    }

    @Test void retryAfterCannotBeBypassedByManualRefreshAndGoneStopsPolling() {
        var subscription = create(1, false);
        when(transport.fetch(anyString(), any(), any())).thenReturn(response(429, "", null, "7200")); collect();
        when(clock.millis()).thenReturn(NOW + 61_000);
        subscriptions.refresh(1, subscription.id());
        assertThat(subscriptions.get(1, subscription.id()).nextFetchAt()).isEqualTo(NOW + 7200_000);
        assertThat(polls.claim()).isEmpty();
        due(subscription.id());
        when(transport.fetch(anyString(), any(), any())).thenReturn(response(410, "", null, null)); collect();
        assertThat(subscriptions.get(1, subscription.id()).status()).isEqualTo("GONE");
        assertThat(polls.claim()).isEmpty();
    }

    @Test void stateConflictsAndBulkReadDoNotOverwriteFutureIngest() {
        var subscription = create(1, false); collect();
        var first = entries.list(1, ALL, null, 30).items().getFirst();
        entries.state(1, Long.parseLong(first.id()), new StateChange(0L, true, false));
        assertThatThrownBy(() -> entries.state(1, Long.parseLong(first.id()), new StateChange(0L, false, true)))
                .isInstanceOfSatisfying(RssException.class, error -> assertThat(error.code()).isEqualTo(ErrorCode.RSS_009));
        entries.markRead(1, new MarkRead(null, null, null));
        when(transport.fetch(anyString(), any(), any())).thenReturn(response(200, feed("b", "Future"), null, null));
        due(subscription.id()); collect();
        assertThat(entries.counts(1).unread()).isEqualTo(1);
    }

    @Test void cancelledSubscriptionsKeepStarsAndRestoreOriginalIdentity() {
        var subscription = create(1, false); collect();
        var first = entries.list(1, ALL, null, 30).items().getFirst();
        entries.state(1, Long.parseLong(first.id()), new StateChange(0L, false, true));
        subscriptions.remove(1, subscription.id());
        assertThat(entries.list(1, ALL, null, 30).items()).isEmpty();
        assertThat(entries.list(1, new Filter(null, null, "starred", null), null, 30).items()).hasSize(1);
        assertThat(create(1, false).id()).isEqualTo(subscription.id());
        assertThat(entries.get(1, Long.parseLong(first.id())).starred()).isTrue();
    }

    @Test void cursorBindsOwnerAndFiltersAndExcludesNewRows() {
        var subscription = create(1, false);
        when(transport.fetch(anyString(), any(), any())).thenReturn(response(200, "<rss version='2.0'><channel><item><guid>a</guid><title>A</title></item><item><guid>b</guid><title>B</title></item></channel></rss>", null, null)); collect();
        var page = entries.list(1, ALL, null, 1);
        when(transport.fetch(anyString(), any(), any())).thenReturn(response(200, feed("c", "C"), null, null)); due(subscription.id()); collect();
        assertThat(entries.list(1, ALL, page.nextCursor(), 1).items()).extracting(Entry::title).containsExactly("B");
        assertThatThrownBy(() -> entries.list(2, ALL, page.nextCursor(), 1)).isInstanceOf(RssException.class);
        assertThatThrownBy(() -> entries.list(1, new Filter(null, null, "starred", null), page.nextCursor(), 1)).isInstanceOf(RssException.class);
    }

    @Test void retentionKeepsTombstoneAndStarredContent() {
        var subscription = create(1, false); collect();
        var first = entries.list(1, ALL, null, 30).items().getFirst();
        entries.state(1, Long.parseLong(first.id()), new StateChange(0L, true, true));
        when(clock.millis()).thenReturn(NOW + 91L * 86400_000);
        assertThat(entries.purge(1)).isZero();
        entries.state(1, Long.parseLong(first.id()), new StateChange(1L, true, false));
        assertThat(entries.purge(1)).isEqualTo(1);
        due(subscription.id()); collect();
        assertThat(entries.list(1, ALL, null, 30).items()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rss_entry", Long.class)).isEqualTo(1);
    }

    @Test void opmlPartialImportAndGroupDeletionPreserveSubscriptions() {
        String xml = "<opml><body><outline text='Tech'><outline text='Feed' xmlUrl='https://example.com/feed'/><outline text='Again' xmlUrl='https://example.com/feed'/><outline text='Bad' xmlUrl='http://127.0.0.1/private'/></outline></body></opml>";
        assertThat(opml.preview(1, xml)).extracting(ImportItem::status).containsExactly("NEW", "DUPLICATE", "INVALID");
        assertThat(subscriptions.list(1)).isEmpty();
        assertThat(opml.importXml(1, xml)).extracting(ImportItem::status).containsExactly("IMPORTED", "DUPLICATE", "INVALID");
        assertThat(subscriptions.list(1)).hasSize(1);
        assertThat(opml.export(2)).doesNotContain(URL);
        String groupId = subscriptions.groups(1).getFirst().id();
        assertThatThrownBy(() -> subscriptions.removeGroup(2, groupId)).isInstanceOf(RssException.class);
        subscriptions.removeGroup(1, groupId);
        assertThat(subscriptions.list(1).getFirst().groupId()).isNull();
    }

    @Test void discoveryIsReadOnlyAndRateLimited() {
        var preview = discovery.discover(1, URL);
        assertThat(preview.preview().entries()).hasSize(1);
        assertThat(subscriptions.list(1)).isEmpty();
        assertThatThrownBy(() -> discovery.discover(1, URL)).isInstanceOfSatisfying(RssException.class, exception -> assertThat(exception.code()).isEqualTo(ErrorCode.RSS_007));
    }

    @Test void authenticationAndUnknownOwnerFieldsAreRejected() throws Exception {
        mvc.perform(get("/internal/v1/rss/subscriptions").header("X-RSS-Owner", "1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/rss/subscriptions").header("Authorization", "Bearer offline-rss-integration-token-32-characters")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/rss/subscriptions").header("Authorization", "Bearer offline-rss-integration-token-32-characters").header("X-RSS-Owner", "1")
                .contentType("application/json").content("{\"url\":\"https://example.com/feed\",\"ownerId\":2}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RSS_001"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    private Subscription create(long owner, boolean read) { return subscriptions.create(owner, new CreateSubscription(URL, "", null, read)); }
    private void collect() { polling.collect(polls.claim().orElseThrow()); }
    private void due(String id) { jdbc.update("UPDATE rss_subscription SET next_fetch_at=0,lease_until=0 WHERE id=?", id); }
    private String feed(String id, String title) { return "<rss version='2.0'><channel><title>Feed</title><item><guid>" + id + "</guid><title>" + title + "</title><description><![CDATA[<p>Body</p>]]></description></item></channel></rss>"; }
    private FeedModels.Fetch response(int status, String xml, String etag, String retry) { return new FeedModels.Fetch(status, xml.getBytes(StandardCharsets.UTF_8), URL, etag, null, retry, "application/rss+xml"); }
}
