package com.yuyutian.mytools.reader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.config.ReaderRuntimeProperties;
import com.yuyutian.mytools.reader.mapper.BookSourceSearchCacheMapper;
import com.yuyutian.mytools.reader.mapper.SyncedBookSourceMapper;
import com.yuyutian.mytools.reader.model.BookSourceSearchCache;
import com.yuyutian.mytools.reader.model.BookSourceRuntimeReaderModels;
import com.yuyutian.mytools.reader.model.BookSourceRuntimeSearchModels;
import com.yuyutian.mytools.reader.model.SyncedBookSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookSourceRuntimeSearchServiceTest {

    @Test
    void acceptsResultWhenFirstChapterCanBeRead() {
        ReaderRuntimeClient runtimeClient = mock(ReaderRuntimeClient.class);
        BookSourceRuntimeSearchModels.SearchResult result = result();
        when(runtimeClient.catalog(7L, result.sourceUrl(), result.bookUrl())).thenReturn(catalog());
        when(runtimeClient.content(7L, result.sourceUrl(), "https://book.example/1", 0))
                .thenReturn(new BookSourceRuntimeReaderModels.Content("text", "chapter body", List.of()));

        assertThat(service(runtimeClient).isReadable(7L, result)).isTrue();
    }

    @Test
    void rejectsResultWhenFirstChapterCannotBeRead() {
        ReaderRuntimeClient runtimeClient = mock(ReaderRuntimeClient.class);
        BookSourceRuntimeSearchModels.SearchResult result = result();
        when(runtimeClient.catalog(7L, result.sourceUrl(), result.bookUrl())).thenReturn(catalog());
        when(runtimeClient.content(7L, result.sourceUrl(), "https://book.example/1", 0))
                .thenThrow(new IllegalStateException("first chapter unavailable"));

        assertThat(service(runtimeClient).isReadable(7L, result)).isFalse();
    }

    @Test
    void rejectsSearchOnlyResultWhenCatalogCannotBeRead() {
        ReaderRuntimeClient runtimeClient = mock(ReaderRuntimeClient.class);
        BookSourceRuntimeSearchModels.SearchResult result = result();
        when(runtimeClient.catalog(7L, result.sourceUrl(), result.bookUrl()))
                .thenThrow(new IllegalStateException("catalog unavailable"));

        assertThat(service(runtimeClient).isReadable(7L, result)).isFalse();
    }

    @Test
    void appliesExactAndFuzzyMatchingRules() {
        BookSourceRuntimeSearchService service = service(mock(ReaderRuntimeClient.class));
        BookSourceRuntimeSearchModels.SearchResult value = new BookSourceRuntimeSearchModels.SearchResult(
                "The Hero Returns", "Writer", "A lost prince returns home", "Finale", "", "book", "source", "Source");

        assertThat(service.matches(value, "The Hero Returns", "EXACT")).isTrue();
        assertThat(service.matches(value, "Hero", "EXACT")).isFalse();
        assertThat(service.matches(value, "lost prince", "FUZZY")).isTrue();
        assertThat(service.matches(value, "missing", "FUZZY")).isFalse();
    }

    @Test
    void deduplicatesEquivalentTitlesAcrossSources() {
        BookSourceRuntimeSearchService service = service(mock(ReaderRuntimeClient.class));
        BookSourceRuntimeSearchModels.SearchResult first = result();
        BookSourceRuntimeSearchModels.SearchResult second = new BookSourceRuntimeSearchModels.SearchResult(
                "Book！", " Author ", "", "", "", "another", "other", "Other");

        assertThat(service.canonicalResultKey(first)).isEqualTo(service.canonicalResultKey(second));
    }

    @Test
    void reusesCachedResultWithoutCallingRuntimeSearch() throws Exception {
        SyncedBookSourceMapper sourceMapper = mock(SyncedBookSourceMapper.class);
        BookSourceSearchCacheMapper cacheMapper = mock(BookSourceSearchCacheMapper.class);
        ReaderRuntimeClient runtimeClient = mock(ReaderRuntimeClient.class);
        when(sourceMapper.findAllByUserId(7L)).thenReturn(List.of(source("source-1", 3L)));
        when(cacheMapper.findValidForSearch(eq(7L), eq("hello"), eq("FUZZY"), eq(1), anyLong()))
                .thenReturn(List.of(cache("hello", "FUZZY", "source-1", 3L,
                        new ObjectMapper().writeValueAsString(List.of(result())), 1)));
        BookSourceRuntimeSearchService service = service(sourceMapper, cacheMapper, runtimeClient);

        BookSourceRuntimeSearchModels.Task completed = service.start(7L, " hello ", 1, "FUZZY");

        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.resultCount()).isEqualTo(1);
        assertThat(completed.cachedSources()).isEqualTo(1);
        assertThat(completed.pendingSources()).isZero();
        assertThat(completed.message()).contains("缓存命中1个").contains("实际查询0个");
        verify(runtimeClient, never()).search(anyLong(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void reusesNegativeCacheWithoutCallingRuntimeSearch() {
        SyncedBookSourceMapper sourceMapper = mock(SyncedBookSourceMapper.class);
        BookSourceSearchCacheMapper cacheMapper = mock(BookSourceSearchCacheMapper.class);
        ReaderRuntimeClient runtimeClient = mock(ReaderRuntimeClient.class);
        when(sourceMapper.findAllByUserId(7L)).thenReturn(List.of(source("source-1", 3L)));
        when(cacheMapper.findValidForSearch(eq(7L), eq("missing"), eq("EXACT"), eq(1), anyLong()))
                .thenReturn(List.of(cache("missing", "EXACT", "source-1", 3L, "[]", 0)));
        BookSourceRuntimeSearchService service = service(sourceMapper, cacheMapper, runtimeClient);

        BookSourceRuntimeSearchModels.Task completed = service.start(7L, "missing", 1, "EXACT");

        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.resultCount()).isZero();
        assertThat(completed.cachedSources()).isEqualTo(1);
        assertThat(completed.pendingSources()).isZero();
        assertThat(completed.message()).contains("未找到结果").contains("缓存命中1个");
        verify(runtimeClient, never()).search(anyLong(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void cachedProbeSearchSkipsProbeAnalysis() {
        SyncedBookSourceMapper sourceMapper = mock(SyncedBookSourceMapper.class);
        BookSourceSearchCacheMapper cacheMapper = mock(BookSourceSearchCacheMapper.class);
        ReaderRuntimeClient runtimeClient = mock(ReaderRuntimeClient.class);
        BookSourceProbeQueryService probeQueryService = mock(BookSourceProbeQueryService.class);
        when(sourceMapper.findAllByUserId(7L)).thenReturn(List.of(source("source-1", 3L)));
        when(cacheMapper.findValidForSearch(eq(7L), eq("characterclue"), eq("PROBE"), eq(1), anyLong()))
                .thenReturn(List.of(cache("characterclue", "PROBE", "source-1", 3L, "[]", 0)));
        BookSourceRuntimeSearchService service = service(sourceMapper, cacheMapper, runtimeClient, probeQueryService);

        BookSourceRuntimeSearchModels.Task completed = service.start(7L, "Character clue", 1, "PROBE");

        assertThat(completed.status()).isEqualTo("COMPLETED");
        verify(probeQueryService, never()).analyze(anyLong(), anyString(), any());
        verify(runtimeClient, never()).search(anyLong(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void ignoresCacheFromOlderSourceRevision() {
        SyncedBookSourceMapper sourceMapper = mock(SyncedBookSourceMapper.class);
        BookSourceSearchCacheMapper cacheMapper = mock(BookSourceSearchCacheMapper.class);
        ReaderRuntimeClient runtimeClient = mock(ReaderRuntimeClient.class);
        when(sourceMapper.findAllByUserId(7L)).thenReturn(List.of(source("source-1", 4L)));
        when(cacheMapper.findValidForSearch(eq(7L), eq("updated"), eq("FUZZY"), eq(1), anyLong()))
                .thenReturn(List.of(cache("updated", "FUZZY", "source-1", 3L, "[]", 0)));
        when(runtimeClient.search(7L, "https://source.example", "Source", "updated", 1))
                .thenReturn(List.of());
        BookSourceRuntimeSearchService service = service(sourceMapper, cacheMapper, runtimeClient);

        awaitCompleted(service, service.start(7L, "updated", 1, "FUZZY").taskId());

        verify(runtimeClient).search(7L, "https://source.example", "Source", "updated", 1);
        ArgumentCaptor<BookSourceSearchCache> captor = ArgumentCaptor.forClass(BookSourceSearchCache.class);
        verify(cacheMapper).upsert(captor.capture());
        assertThat(captor.getValue().sourceRevision()).isEqualTo(4L);
    }

    @Test
    void storesSuccessfulEmptySearchForThreeDays() {
        SyncedBookSourceMapper sourceMapper = mock(SyncedBookSourceMapper.class);
        BookSourceSearchCacheMapper cacheMapper = mock(BookSourceSearchCacheMapper.class);
        ReaderRuntimeClient runtimeClient = mock(ReaderRuntimeClient.class);
        when(sourceMapper.findAllByUserId(7L)).thenReturn(List.of(source("source-1", 3L)));
        when(runtimeClient.search(7L, "https://source.example", "Source", "missing", 1))
                .thenReturn(List.of());
        BookSourceRuntimeSearchService service = service(sourceMapper, cacheMapper, runtimeClient);
        long startedAt = System.currentTimeMillis();

        awaitCompleted(service, service.start(7L, "missing", 1, "FUZZY").taskId());

        ArgumentCaptor<BookSourceSearchCache> captor = ArgumentCaptor.forClass(BookSourceSearchCache.class);
        verify(cacheMapper).upsert(captor.capture());
        BookSourceSearchCache saved = captor.getValue();
        assertThat(saved.resultsJson()).isEqualTo("[]");
        assertThat(saved.cacheStatus()).isEqualTo("EMPTY");
        assertThat(saved.resultCount()).isZero();
        assertThat(saved.expiresAt() - saved.createdAt()).isEqualTo(Duration.ofDays(3).toMillis());
        assertThat(saved.createdAt()).isGreaterThanOrEqualTo(startedAt);
    }

    @Test
    void limitsConcurrentSourceSearchesToTwenty() throws Exception {
        SyncedBookSourceMapper sourceMapper = mock(SyncedBookSourceMapper.class);
        BookSourceSearchCacheMapper cacheMapper = mock(BookSourceSearchCacheMapper.class);
        ReaderRuntimeClient runtimeClient = mock(ReaderRuntimeClient.class);
        List<SyncedBookSource> sources = new ArrayList<>();
        for (int index = 0; index < 21; index++) sources.add(source("source-" + index, 1L));
        when(sourceMapper.findAllByUserId(7L)).thenReturn(sources);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch twentyStarted = new CountDownLatch(20);
        CountDownLatch release = new CountDownLatch(1);
        when(runtimeClient.search(anyLong(), anyString(), anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            twentyStarted.countDown();
            release.await(3, TimeUnit.SECONDS);
            active.decrementAndGet();
            return List.of();
        });
        BookSourceRuntimeSearchService service = service(sourceMapper, cacheMapper, runtimeClient);

        String taskId = service.start(7L, "parallel", 1, "FUZZY").taskId();
        assertThat(twentyStarted.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(maximum.get()).isEqualTo(20);
        release.countDown();
        assertThat(awaitCompleted(service, taskId).processedSources()).isEqualTo(21);
        assertThat(maximum.get()).isEqualTo(20);
    }

    @Test
    void storesUnreadableCandidatesForThreeDays() {
        SyncedBookSourceMapper sourceMapper = mock(SyncedBookSourceMapper.class);
        BookSourceSearchCacheMapper cacheMapper = mock(BookSourceSearchCacheMapper.class);
        ReaderRuntimeClient runtimeClient = mock(ReaderRuntimeClient.class);
        when(sourceMapper.findAllByUserId(7L)).thenReturn(List.of(source("source-1", 3L)));
        when(runtimeClient.search(7L, "https://source.example", "Source", "book", 1))
                .thenReturn(List.of(result()));
        when(runtimeClient.catalog(7L, result().sourceUrl(), result().bookUrl()))
                .thenThrow(new IllegalStateException("catalog unavailable"));
        BookSourceRuntimeSearchService service = service(sourceMapper, cacheMapper, runtimeClient);

        BookSourceRuntimeSearchModels.Task completed = awaitCompleted(service,
                service.start(7L, "book", 1, "FUZZY").taskId());

        ArgumentCaptor<BookSourceSearchCache> captor = ArgumentCaptor.forClass(BookSourceSearchCache.class);
        verify(cacheMapper).upsert(captor.capture());
        assertThat(captor.getValue().cacheStatus()).isEqualTo("UNREADABLE");
        assertThat(captor.getValue().expiresAt() - captor.getValue().createdAt())
                .isEqualTo(Duration.ofDays(3).toMillis());
        assertThat(completed.failedSources()).isZero();
    }

    @Test
    void storesRuntimeFailureWithShortRetryWindow() {
        SyncedBookSourceMapper sourceMapper = mock(SyncedBookSourceMapper.class);
        BookSourceSearchCacheMapper cacheMapper = mock(BookSourceSearchCacheMapper.class);
        ReaderRuntimeClient runtimeClient = mock(ReaderRuntimeClient.class);
        when(sourceMapper.findAllByUserId(7L)).thenReturn(List.of(source("source-1", 3L)));
        when(runtimeClient.search(7L, "https://source.example", "Source", "book", 1))
                .thenThrow(new IllegalStateException("runtime unavailable"));
        BookSourceRuntimeSearchService service = service(sourceMapper, cacheMapper, runtimeClient);

        BookSourceRuntimeSearchModels.Task completed = awaitCompleted(service,
                service.start(7L, "book", 1, "FUZZY").taskId());

        ArgumentCaptor<BookSourceSearchCache> captor = ArgumentCaptor.forClass(BookSourceSearchCache.class);
        verify(cacheMapper).upsert(captor.capture());
        assertThat(captor.getValue().cacheStatus()).isEqualTo("ERROR");
        assertThat(captor.getValue().expiresAt() - captor.getValue().createdAt())
                .isEqualTo(Duration.ofMinutes(15).toMillis());
        assertThat(completed.failedSources()).isEqualTo(1);
    }

    @Test
    void reusesRecentRuntimeFailureWithoutImmediateRetry() {
        SyncedBookSourceMapper sourceMapper = mock(SyncedBookSourceMapper.class);
        BookSourceSearchCacheMapper cacheMapper = mock(BookSourceSearchCacheMapper.class);
        ReaderRuntimeClient runtimeClient = mock(ReaderRuntimeClient.class);
        when(sourceMapper.findAllByUserId(7L)).thenReturn(List.of(source("source-1", 3L)));
        when(cacheMapper.findValidForSearch(eq(7L), eq("book"), eq("FUZZY"), eq(1), anyLong()))
                .thenReturn(List.of(cache("book", "FUZZY", "source-1", 3L, "ERROR", "[]", 0)));
        BookSourceRuntimeSearchService service = service(sourceMapper, cacheMapper, runtimeClient);

        BookSourceRuntimeSearchModels.Task completed = service.start(7L, "book", 1, "FUZZY");

        assertThat(completed.cachedSources()).isEqualTo(1);
        assertThat(completed.pendingSources()).isZero();
        assertThat(completed.failedSources()).isEqualTo(1);
        verify(runtimeClient, never()).search(anyLong(), anyString(), anyString(), anyString(), anyInt());
    }

    private BookSourceRuntimeSearchService service(ReaderRuntimeClient runtimeClient) {
        return service(mock(SyncedBookSourceMapper.class), mock(BookSourceSearchCacheMapper.class), runtimeClient);
    }

    private BookSourceRuntimeSearchService service(SyncedBookSourceMapper sourceMapper,
                                                   BookSourceSearchCacheMapper cacheMapper,
                                                   ReaderRuntimeClient runtimeClient) {
        return service(sourceMapper, cacheMapper, runtimeClient, mock(BookSourceProbeQueryService.class));
    }

    private BookSourceRuntimeSearchService service(SyncedBookSourceMapper sourceMapper,
                                                   BookSourceSearchCacheMapper cacheMapper,
                                                   ReaderRuntimeClient runtimeClient,
                                                   BookSourceProbeQueryService probeQueryService) {
        return new BookSourceRuntimeSearchService(sourceMapper, cacheMapper, new ObjectMapper(), runtimeClient,
                new ReaderRuntimeProperties(), probeQueryService, new SimpleMeterRegistry(),
                mock(ApplicationEventPublisher.class));
    }

    private BookSourceRuntimeSearchModels.Task awaitCompleted(BookSourceRuntimeSearchService service, String taskId) {
        long deadline = System.currentTimeMillis() + 5000;
        BookSourceRuntimeSearchModels.Task task;
        do {
            task = service.find(7L, taskId, 0, 200);
            if (!"RUNNING".equals(task.status())) return task;
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("search task did not complete");
    }

    private SyncedBookSource source(String syncKey, long revision) {
        SyncedBookSource source = new SyncedBookSource();
        source.setUserId(7L);
        source.setSyncKey(syncKey);
        source.setSourceUrl("https://source.example");
        source.setSnapshotJson("{\"bookSourceUrl\":\"https://source.example\","
                + "\"bookSourceName\":\"Source\",\"enabled\":true}");
        source.setRevision(revision);
        return source;
    }

    private BookSourceSearchCache cache(String keyword, String mode, String sourceId, long revision,
                                        String resultsJson, int resultCount) {
        return cache(keyword, mode, sourceId, revision, resultCount > 0 ? "RESULT" : "EMPTY",
                resultsJson, resultCount);
    }

    private BookSourceSearchCache cache(String keyword, String mode, String sourceId, long revision,
                                        String cacheStatus, String resultsJson, int resultCount) {
        long now = System.currentTimeMillis();
        return new BookSourceSearchCache(7L, keyword, mode, sourceId, 1, revision, cacheStatus, resultsJson,
                resultCount, now, now + Duration.ofDays(3).toMillis());
    }

    private BookSourceRuntimeSearchModels.SearchResult result() {
        return new BookSourceRuntimeSearchModels.SearchResult("Book", "Author", "", "", "",
                "https://book.example", "https://source.example", "Source");
    }

    private BookSourceRuntimeReaderModels.Catalog catalog() {
        return new BookSourceRuntimeReaderModels.Catalog("Book", "Author", "", "", "",
                List.of(new BookSourceRuntimeReaderModels.Chapter("Preface", "https://book.example/1", 0),
                        new BookSourceRuntimeReaderModels.Chapter("Chapter", "https://book.example/2", 1)));
    }
}
