package com.yuyutian.mytools.rss.service;

import com.yuyutian.mytools.feed.*;
import com.yuyutian.mytools.rss.Model.RssModels.*;
import com.yuyutian.mytools.rss.common.ErrorCode;
import com.yuyutian.mytools.rss.common.RssException;
import com.yuyutian.mytools.rss.mapper.SubscriptionRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** 个人 OPML 导入导出，逐项失败不回滚其他成功订阅。 */
@Service
public class OpmlService {
    private final SubscriptionRepository subscriptions;
    private final OpmlCodec codec = new OpmlCodec();
    /** 注入有 owner 隔离的订阅仓储。 */
    public OpmlService(SubscriptionRepository subscriptions) { this.subscriptions = subscriptions; }
    /** 预览不抓取远端内容，也不写入分组或订阅。 */
    public List<ImportItem> preview(long owner, String xml) {
        var known = new HashMap<String, String>();
        for (Subscription subscription : subscriptions.list(owner)) known.put(subscription.feedUrl(), subscription.id());
        var results = new ArrayList<ImportItem>();
        for (var outline : codec.parse(xml.getBytes(StandardCharsets.UTF_8))) {
            try {
                String url = FeedParser.canonical(PublicNetworkPolicy.requireHttps(outline.url().strip()).toString());
                String state = known.containsKey(url) ? "DUPLICATE" : "NEW";
                results.add(new ImportItem(url, outline.title(), outline.group(), state, known.get(url), null));
                known.putIfAbsent(url, "");
            } catch (FeedException | IllegalArgumentException exception) {
                results.add(new ImportItem(FeedParser.truncate(outline.url(), 2048), outline.title(), outline.group(), "INVALID", null, ErrorCode.RSS_008.name()));
            }
        }
        return results;
    }
    /** 按预览候选逐项导入，重复项保留旧分组与阅读状态。 */
    public List<ImportItem> importXml(long owner, String xml) {
        var output = new ArrayList<ImportItem>();
        Map<String, String> groups = new HashMap<>();
        for (Group group : subscriptions.groups(owner)) groups.put(group.name(), group.id());
        for (ImportItem item : preview(owner, xml)) {
            if (!item.status().equals("NEW")) { output.add(item); continue; }
            try {
                String groupId = null;
                if (!item.group().isBlank()) {
                    groupId = groups.computeIfAbsent(item.group(), name -> subscriptions.createGroup(owner, new GroupChange(name, 0, null)).id());
                }
                var subscription = subscriptions.create(owner, new CreateSubscription(item.url(), item.title(), groupId, false));
                output.add(new ImportItem(item.url(), item.title(), item.group(), "IMPORTED", subscription.id(), null));
            } catch (RssException exception) { output.add(new ImportItem(item.url(), item.title(), item.group(), "FAILED", null, exception.code().name())); }
            catch (DataIntegrityViolationException exception) { output.add(new ImportItem(item.url(), item.title(), item.group(), "FAILED", null, ErrorCode.RSS_009.name())); }
        }
        return output;
    }
    /** 仅导出本人有效订阅；原始地址只返回给已认证的所有者。 */
    public String export(long owner) {
        Map<String, String> groups = new HashMap<>();
        for (Group group : subscriptions.groups(owner)) groups.put(group.id(), group.name());
        return codec.write(subscriptions.list(owner).stream().map(subscription -> new FeedModels.Outline(subscription.feedUrl(),
                subscription.customTitle().isBlank() ? subscription.title() : subscription.customTitle(), groups.getOrDefault(subscription.groupId(), ""))).toList());
    }
}
