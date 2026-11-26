<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import type { NewsCategory, NewsEdition } from '@/service/api/news.types';
import { useNewsText } from '@/locales/news';
import { useNewsEvidenceText } from '@/locales/news-evidence';
import { newsEvidenceApi } from '@/service/api/news';
import { createEvidenceWorkspace } from '../evidence-model';
import { newsCategories, newsTime, safeNewsUrl } from '../model';

const props = defineProps<{ edition: NewsEdition }>();
const { text, status, locale } = useNewsText();
const active = ref<NewsCategory>('TECH');
const evidenceText = useNewsEvidenceText();
const evidence = createEvidenceWorkspace(newsEvidenceApi);
const evidenceState = evidence.state;
onBeforeUnmount(evidence.dispose);
const categoryNames = {
  TECH: ['Technology', '\u6280\u672f'],
  WORLD: ['World', '\u56fd\u9645'],
  DOMESTIC: ['Domestic', '\u56fd\u5185'],
  LOCAL: ['Local', '\u672c\u5730']
};
watch(
  () => props.edition.id,
  () => {
    active.value = 'TECH';
    void evidence.load(props.edition.id);
  },
  { immediate: true }
);
</script>

<template>
  <article class="news-reader" data-testid="news-reader">
    <header class="edition-heading">
      <div>
        <p class="overline">{{ text.edition }}</p>
        <h2>{{ newsTime(edition.cutoff, edition.zone, locale) }}</h2>
        <p class="muted">{{ edition.zone }} · {{ text.cutoff }}</p>
      </div>
      <span class="quality" :class="{ incomplete: edition.quality !== 'COMPLETE' }">{{ status(edition.quality) }}</span>
    </header>
    <p class="muted">{{ text.topNote }}</p>
    <div class="region-snapshot">
      <strong>{{ text.frozenRegion }}</strong>
      <span>
        {{
          edition.region.details?.sourcePackLabel ||
            edition.region.details?.cityName ||
            edition.region.regionCode ||
            text.regionUnknown
        }}
      </span>
      <span>{{ status(edition.region.status) }}</span>
      <small v-if="edition.region.nodeId">{{ text.node }}: {{ edition.region.nodeId }}</small>
    </div>
    <aside class="evidence-overview" data-testid="evidence-overview" aria-live="polite">
      <strong>{{ evidenceText.title }}</strong>
      <p>{{ evidenceText.note }}</p>
      <p v-if="evidenceState.checkedAt">
        {{ evidenceText.checked }}: {{ newsTime(evidenceState.checkedAt, edition.zone, locale) }}
      </p>
      <p v-if="evidenceState.failed" role="status" data-testid="evidence-error">{{ evidenceText.failed }}</p>
      <button
        type="button"
        :disabled="evidenceState.loading"
        data-testid="refresh-evidence"
        @click="evidence.load(edition.id)"
      >
        {{ evidenceState.loading ? evidenceText.loading : evidenceText.refresh }}
      </button>
    </aside>
    <nav class="category-tabs" :aria-label="text.edition">
      <button
        v-for="category in newsCategories"
        :key="category"
        type="button"
        :aria-pressed="active === category"
        :class="{ active: active === category }"
        :data-testid="`category-${category}`"
        @click="active = category"
      >
        {{ categoryNames[category][locale === 'zh-CN' ? 1 : 0] }}
        <span>{{ edition.sections[category]?.headlineCount ?? 0 }}</span>
      </button>
    </nav>
    <section v-if="edition.sections[active]" :aria-label="categoryNames[active][locale === 'zh-CN' ? 1 : 0]">
      <div class="section-summary">
        <strong>{{ text.headlines }} {{ edition.sections[active].headlineCount }}/20</strong>
        <span>{{ text.details }} {{ edition.sections[active].detailCount }}/10</span>
        <span>{{ text.candidates }} {{ edition.sections[active].candidateCount }}</span>
      </div>
      <p class="muted window">
        {{ text.window }}: {{ newsTime(edition.sections[active].windowStart, edition.zone, locale) }} —
        {{ newsTime(edition.sections[active].windowEnd, edition.zone, locale) }}
      </p>
      <ul v-if="edition.sections[active].gaps.length" class="gaps" aria-live="polite">
        <li v-for="gap in edition.sections[active].gaps" :key="gap">{{ status(gap) }}</li>
      </ul>
      <p v-if="!edition.sections[active].items.length" class="empty">{{ text.emptySection }}</p>
      <ol class="stories">
        <li v-for="item in edition.sections[active].items" :key="item.eventId" :value="item.rank" class="story">
          <div class="story-heading">
            <span class="rank" aria-hidden="true">{{ String(item.rank).padStart(2, '0') }}</span>
            <div>
              <h3>
                <a v-if="safeNewsUrl(item.url)" :href="safeNewsUrl(item.url)" target="_blank" rel="noopener noreferrer">
                  {{ item.title }}
                </a>
                <span v-else>{{ item.title }}</span>
              </h3>
              <p class="muted">
                {{ text.sourceIds }}: {{ item.sourceIds.join(' · ') }}
                <span v-if="item.publishedAt">
                  · {{ text.published }} {{ newsTime(item.publishedAt, edition.zone, locale) }}
                </span>
              </p>
            </div>
          </div>
          <div v-if="item.technical?.github" class="trend">
            <strong>{{ text.trend }}</strong>
            · {{ status(item.technical.github.status, 'trend') }}
            <span>{{ text.netStars }}: {{ item.technical.github.netStars ?? '—' }}</span>
            <span>
              {{ text.stars }}: {{ item.technical.github.stars }} · {{ text.samples }}:
              {{ item.technical.github.sampleCount }}
            </span>
            <small>{{ text.trendHint }}</small>
          </div>
          <template v-if="item.rank <= 10">
            <div v-if="item.detail" class="detail">
              <span class="excerpt-label">{{ text.excerpt }}</span>
              <p>{{ item.detail.text }}</p>
              <details>
                <summary>{{ text.evidence }} ({{ item.detail.citations.length }})</summary>
                <ul>
                  <li v-for="citation in item.detail.citations" :key="citation.articleId">
                    <a
                      v-if="safeNewsUrl(citation.url)"
                      :href="safeNewsUrl(citation.url)"
                      target="_blank"
                      rel="noopener noreferrer"
                    >
                      {{ citation.sourceId }}
                    </a>
                    <span v-else>{{ citation.sourceId }}</span>
                    · {{ text.observed }} {{ newsTime(citation.fetchedAt, edition.zone, locale) }}
                    <span class="evidence-state" :data-testid="`evidence-${citation.articleId}`">
                      {{ evidenceText[evidenceState.items[citation.articleId]?.status || 'PENDING'] }}
                      <span v-if="evidenceState.items[citation.articleId]?.expiresAt">
                        · {{ evidenceText.expires }}:
                        {{ newsTime(evidenceState.items[citation.articleId].expiresAt, edition.zone, locale) }}
                      </span>
                    </span>
                  </li>
                </ul>
              </details>
            </div>
            <p v-else class="missing-detail">{{ status(item.detailStatus) }}</p>
          </template>
          <a
            v-if="safeNewsUrl(item.technical?.discussionUrl)"
            class="discussion"
            :href="safeNewsUrl(item.technical?.discussionUrl)"
            target="_blank"
            rel="noopener noreferrer"
          >
            {{ text.discussion }} ↗
          </a>
        </li>
      </ol>
    </section>
    <footer class="muted">{{ text.qualityHint }}</footer>
  </article>
</template>

<style scoped>
.news-reader {
  min-width: 0;
}
.edition-heading {
  display: flex;
  align-items: start;
  justify-content: space-between;
  gap: 16px;
}
h2 {
  font-size: 24px;
  font-weight: 650;
  line-height: 1.4;
}
.overline {
  font-size: 12px;
  letter-spacing: 0.1em;
  margin-bottom: 8px;
  color: #527567;
}
.muted {
  opacity: 0.68;
  font-size: 12px;
  line-height: 1.8;
  margin: 8px 0;
}
.quality {
  flex-shrink: 0;
  color: #126747;
  background: #e8f5ee;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
}
.quality.incomplete {
  color: #875613;
  background: #fcf1db;
}
.region-snapshot {
  margin: 20px 0;
  border: 1px solid #a5afa733;
  border-radius: 8px;
  padding: 12px 16px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  font-size: 12px;
}
.evidence-overview {
  border-left: 3px solid #6f927f;
  background: #6387740a;
  padding: 12px 16px;
  font-size: 12px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.evidence-overview p {
  margin: 6px 0;
  opacity: 0.8;
}
.evidence-overview button {
  color: #278263;
  text-decoration: underline;
  cursor: pointer;
  padding: 4px 0;
}
.evidence-overview button:disabled {
  cursor: wait;
  opacity: 0.6;
}
.evidence-state {
  display: block;
}
.category-tabs {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border-bottom: 1px solid #a5afa733;
  margin: 24px 0 20px;
}
.category-tabs button {
  cursor: pointer;
  padding: 12px 4px;
  border-bottom: 3px solid transparent;
  font-size: 14px;
}
.category-tabs button.active {
  border-bottom-color: #2d8265;
  color: #278263;
  font-weight: 650;
}
.category-tabs span {
  opacity: 0.6;
  margin-left: 6px;
  font-size: 12px;
}
.section-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 22px;
  font-size: 13px;
}
.gaps {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 20px;
  font-size: 12px;
  color: #976521;
  padding: 12px 0;
  list-style: inside;
}
.stories {
  list-style: none;
  padding: 0;
  margin: 0;
}
.story {
  padding: 24px 0;
  border-bottom: 1px solid #a5afa733;
  overflow-wrap: anywhere;
}
.story-heading {
  display: flex;
  gap: 16px;
}
.rank {
  flex-shrink: 0;
  white-space: nowrap;
  min-width: 2ch;
  font-size: 20px;
  color: #6f927f;
  font-variant-numeric: tabular-nums;
}
h3 {
  font-size: 17px;
  line-height: 1.65;
  font-weight: 600;
}
a:hover {
  text-decoration: underline;
}
a:focus-visible,
button:focus-visible,
summary:focus-visible {
  outline: 2px solid #2d8265;
  outline-offset: 3px;
}
.detail {
  margin: 14px 0 0 40px;
  line-height: 1.9;
  font-size: 14px;
}
.detail p {
  white-space: pre-wrap;
}
.excerpt-label {
  font-size: 11px;
  opacity: 0.6;
}
details {
  font-size: 12px;
  opacity: 0.8;
  margin-top: 12px;
}
summary {
  cursor: pointer;
}
details ul {
  padding-left: 18px;
  list-style: disc;
}
.trend {
  margin: 12px 0 0 40px;
  padding: 10px 12px;
  background: #63877412;
  font-size: 12px;
  line-height: 1.9;
}
.trend span,
.trend small {
  display: block;
}
.missing-detail,
.discussion {
  font-size: 12px;
  margin: 12px 0 0 40px;
  opacity: 0.75;
  display: block;
}
.empty {
  padding: 36px 12px;
  text-align: center;
  opacity: 0.6;
}
footer {
  padding-top: 20px;
}
@media (max-width: 600px) {
  h2 {
    font-size: 18px;
  }
  .edition-heading {
    flex-wrap: wrap;
  }
  .detail,
  .trend,
  .missing-detail,
  .discussion {
    margin-left: 0;
  }
  .story-heading {
    gap: 10px;
  }
  h3 {
    font-size: 16px;
  }
}
</style>
