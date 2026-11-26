package com.yuyutian.mytools.news.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.news.Model.NewsModels.Category;
import com.yuyutian.mytools.news.Model.WeeklyModels.Job;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.utils.PublicNetworkPolicy;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** 在私有报告发布前再次验证窗口、唯一事件、详情及来源，不直接信任工作进程 JSON。 */
@Component
public class WeeklyReportValidator {
    /** 返回字段白名单内的报告，拒绝越界时间、重复事件和虚构数量。 */
    public JsonNode validate(Job job, JsonNode input, Instant now) {
        try {
            check(input != null && input.isObject() && input.toString().length() <= 2_000_000);
            check(job.windowStart().equals(Instant.parse(input.path("windowStart").asText()))
                    && job.windowEnd().equals(Instant.parse(input.path("windowEnd").asText())));
            JsonNode sections = input.path("sections"), failures = input.path("failedSections");
            check(sections.isArray() && failures.isArray() && sections.size()+failures.size()==Category.values().length);
            Set<String> categories = new HashSet<>(), events = new HashSet<>();
            ObjectNode report = input.deepCopy();
            report.retain("rulesVersion", "windowStart", "windowEnd", "sections", "failedSections", "region");
            report.put("id", job.id().toString());
            report.put("generatedAt", now.toString());
            report.put("revision", job.reportRevision()+1);
            report.put("status", failures.isEmpty() ? "SUCCEEDED" : sections.isEmpty() ? "FAILED" : "PARTIAL");
            int total = 0;
            for (JsonNode section : report.path("sections")) {
                check(section.isObject());
                String category = section.path("category").asText();
                Category.valueOf(category);
                check(categories.add(category));
                JsonNode items = section.path("items");
                check(items.isArray() && items.size()<=20);
                ObjectNode clean = (ObjectNode) section;
                clean.retain("category", "items", "rejectedCount");
                clean.put("count", items.size()); clean.put("shortfall",20-items.size());
                total += items.size();
                int rank = 0;
                for (JsonNode item : items) {
                    check(item.isObject() && item.path("rank").isIntegralNumber() && item.path("rank").intValue()==++rank);
                    String event = required(item,"eventId",1,128);
                    check(event.matches("[A-Za-z0-9_-]+") && events.add(event));
                    required(item,"title",1,400); required(item,"summary",40,1800);
                    required(item,"rankingReason",1,600);
                    check(item.path("limitation").isTextual() && item.path("limitation").asText().length()<=600);
                    Instant time = Instant.parse(item.path("publishedAt").asText("").isBlank()
                            ? item.path("activityAt").asText() : item.path("publishedAt").asText());
                    check(!time.isBefore(job.windowStart()) && time.isBefore(job.windowEnd()));
                    strings(item.path("points"),1,5,500);
                    strings(item.path("sources"),1,32,160);
                    strings(item.path("sourceLinks"),1,32,2048);
                    for (JsonNode link : item.path("sourceLinks")) PublicNetworkPolicy.requireHttps(link.asText());
                    ObjectNode article = (ObjectNode)item;
                    article.retain("rank","eventId","title","summary","points","limitation","rankingReason",
                            "publishedAt","activityAt","sources","sourceLinks","image");
                    article.put("category",category);
                    if (article.has("image")) {
                        JsonNode image = article.path("image");
                        check(image.isObject() && required(image,"assetId",64,64).matches("[a-f0-9]{64}"));
                        required(image,"caption",0,800);
                        PublicNetworkPolicy.requireHttps(required(image,"sourceUrl",1,2048));
                        ((ObjectNode) image).retain("assetId","caption","sourceUrl");
                    }
                }
            }
            for (JsonNode failed : report.path("failedSections")) {
                check(failed.isObject());
                String category = failed.path("category").asText();
                Category.valueOf(category);
                check(categories.add(category) && required(failed,"code",1,80).matches("[A-Z][A-Z0-9_]+"));
                ((ObjectNode) failed).retain("category","code");
            }
            if (report.has("region")) {
                check(report.path("region").isObject());
                ((ObjectNode)report.path("region")).retain("status","regionCode");
            }
            report.put("articleCount",total);
            return report;
        } catch (IllegalArgumentException | NewsException exception) {
            throw new NewsException(ErrorCode.NEWS_033);
        }
    }

    /** 进度仅接受阶段、当前分类和已完成数，不能携带任意日志或伪造百分比。 */
    public JsonNode progress(JsonNode input) {
        check(input != null && input.isObject() && input.toString().length()<=4096
                && Set.of("QUEUED","COLLECTING","ENRICHING","MERGING","ANALYZING","SAVING").contains(input.path("stage").asText())
                && input.path("completedCategories").isInt() && input.path("completedCategories").intValue()>=0
                && input.path("completedCategories").intValue()<=10);
        if (!input.path("category").isMissingNode() && !input.path("category").isNull()) {
            try { Category.valueOf(input.path("category").asText()); }
            catch (IllegalArgumentException exception) { throw new NewsException(ErrorCode.NEWS_033); }
        }
        ObjectNode result = input.deepCopy();
        result.retain("stage","category","completedCategories");
        return result;
    }

    private void strings(JsonNode rows, int min, int max, int length) {
        check(rows instanceof ArrayNode && rows.size()>=min && rows.size()<=max);
        for (JsonNode row : rows) check(row.isTextual() && !row.asText().isBlank() && row.asText().length()<=length);
    }
    private String required(JsonNode parent, String key, int min, int max) {
        JsonNode value = parent.path(key);
        check(value.isTextual() && value.asText().strip().length()>=min && value.asText().length()<=max);
        return value.asText();
    }
    private void check(boolean valid) { if (!valid) throw new NewsException(ErrorCode.NEWS_033); }
}
