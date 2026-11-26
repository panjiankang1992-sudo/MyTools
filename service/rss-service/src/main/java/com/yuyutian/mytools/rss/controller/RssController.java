package com.yuyutian.mytools.rss.controller;

import com.yuyutian.mytools.rss.Model.RssModels.*;
import com.yuyutian.mytools.rss.config.RssProperties;
import com.yuyutian.mytools.rss.mapper.EntryRepository;
import com.yuyutian.mytools.rss.mapper.SubscriptionRepository;
import com.yuyutian.mytools.rss.service.DiscoveryService;
import com.yuyutian.mytools.rss.service.OpmlService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 内部用户接口只读取经过认证过滤器建立的 owner。 */
@RestController
@RequestMapping("/internal/v1/rss")
@Validated
public class RssController {
    private final SubscriptionRepository subscriptions;
    private final EntryRepository entries;
    private final DiscoveryService discovery;
    private final OpmlService opml;
    private final RssProperties properties;
    /** 注入 RSS 用户业务。 */
    public RssController(SubscriptionRepository subscriptions, EntryRepository entries, DiscoveryService discovery, OpmlService opml, RssProperties properties) {
        this.subscriptions = subscriptions; this.entries = entries; this.discovery = discovery; this.opml = opml; this.properties = properties;
    }
    /** 返回真实轮询开关和容量。 */
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() { return Map.of("protocols", List.of("RSS_2", "ATOM_1"), "maxSubscriptions", 100, "pollingEnabled", properties.pollingEnabled(), "opml", true); }
    /** 预览或发现订阅。 */
    @PostMapping("/discover")
    public DiscoveryService.Result discover(@Valid @RequestBody DiscoverRequest body, HttpServletRequest request) { return discovery.discover(owner(request), body.url()); }
    /** 返回订阅列表。 */
    @GetMapping("/subscriptions")
    public List<Subscription> subscriptions(HttpServletRequest request) { return subscriptions.list(owner(request)); }
    /** 保存订阅，采集由后台完成。 */
    @PostMapping("/subscriptions")
    public ResponseEntity<Subscription> create(@Valid @RequestBody CreateSubscription body, HttpServletRequest request) { return ResponseEntity.status(201).body(subscriptions.create(owner(request), body)); }
    /** 获取同步状态。 */
    @GetMapping("/subscriptions/{id}")
    public Subscription subscription(@PathVariable UUID id, HttpServletRequest request) { return subscriptions.get(owner(request), id.toString()); }
    /** 显式设置订阅属性，复用 App PUT 请求能力。 */
    @PutMapping("/subscriptions/{id}")
    public Subscription update(@PathVariable UUID id, @Valid @RequestBody UpdateSubscription body, HttpServletRequest request) { return subscriptions.update(owner(request), id.toString(), body); }
    /** 软取消并保留收藏。 */
    @DeleteMapping("/subscriptions/{id}")
    public Map<String, Boolean> remove(@PathVariable UUID id, HttpServletRequest request) { subscriptions.remove(owner(request), id.toString()); return Map.of("removed", true); }
    /** 接受异步刷新请求，不同步等待网络。 */
    @PostMapping("/subscriptions/{id}/refresh")
    public ResponseEntity<Subscription> refresh(@PathVariable UUID id, HttpServletRequest request) { return ResponseEntity.accepted().body(subscriptions.refresh(owner(request), id.toString())); }
    /** 有界检索文章。 */
    @GetMapping("/entries")
    public EntryPage entries(@RequestParam(required = false) String subscriptionId, @RequestParam(required = false) String groupId,
                             @RequestParam(defaultValue = "all") String view, @RequestParam(required = false) String q,
                             @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit,
                             HttpServletRequest request) { return entries.list(owner(request), new Filter(subscriptionId, groupId, view, q), cursor, limit); }
    /** 获取详情不自动标记已读。 */
    @GetMapping("/entries/{id}")
    public Entry entry(@PathVariable @Min(1) long id, HttpServletRequest request) { return entries.get(owner(request), id); }
    /** 按版本设置阅读状态。 */
    @PutMapping("/entries/{id}/state")
    public Entry state(@PathVariable @Min(1) long id, @Valid @RequestBody StateChange body, HttpServletRequest request) { return entries.state(owner(request), id, body); }
    /** 批量已读只影响当前数据库范围。 */
    @PostMapping("/entries/mark-read")
    public Map<String, Integer> markRead(@Valid @RequestBody MarkRead body, HttpServletRequest request) { return entries.markRead(owner(request), body); }
    /** 首页统计。 */
    @GetMapping("/counts")
    public Counts counts(HttpServletRequest request) { return entries.counts(owner(request)); }
    /** 返回分组。 */
    @GetMapping("/groups")
    public List<Group> groups(HttpServletRequest request) { return subscriptions.groups(owner(request)); }
    /** 创建分组。 */
    @PostMapping("/groups")
    public Group createGroup(@Valid @RequestBody GroupChange body, HttpServletRequest request) { return subscriptions.createGroup(owner(request), body); }
    /** 编辑分组。 */
    @PutMapping("/groups/{id}")
    public Group updateGroup(@PathVariable UUID id, @Valid @RequestBody GroupChange body, HttpServletRequest request) { return subscriptions.updateGroup(owner(request), id.toString(), body); }
    /** 删除分组并保留订阅。 */
    @DeleteMapping("/groups/{id}")
    public Map<String, Boolean> deleteGroup(@PathVariable UUID id, HttpServletRequest request) { subscriptions.removeGroup(owner(request), id.toString()); return Map.of("removed", true); }
    /** 预览 OPML 候选。 */
    @PostMapping("/opml/preview")
    public List<ImportItem> previewOpml(@Valid @RequestBody OpmlRequest body, HttpServletRequest request) { return opml.preview(owner(request), body.xml()); }
    /** 导入 OPML 并返回逐项结果。 */
    @PostMapping("/opml/import")
    public List<ImportItem> importOpml(@Valid @RequestBody OpmlRequest body, HttpServletRequest request) { return opml.importXml(owner(request), body.xml()); }
    /** 返回 XML 文本供客户端写入用户选择的文件。 */
    @GetMapping("/opml/export")
    public Map<String, String> exportOpml(HttpServletRequest request) { return Map.of("xml", opml.export(owner(request))); }
    private long owner(HttpServletRequest request) { return (Long) request.getAttribute("rssOwner"); }
}
