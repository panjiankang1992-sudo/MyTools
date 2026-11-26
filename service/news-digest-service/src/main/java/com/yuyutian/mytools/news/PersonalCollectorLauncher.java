package com.yuyutian.mytools.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.Model.DeliveryModels.*;
import com.yuyutian.mytools.news.Model.RegionModels.*;
import com.yuyutian.mytools.news.config.*;
import com.yuyutian.mytools.news.mapper.*;
import com.yuyutian.mytools.news.service.*;
import com.yuyutian.mytools.news.service.technical.*;
import com.yuyutian.mytools.news.common.NewsException;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;

/** 自用采集命令行入口，复用采集与排序，在临时 H2 中生成材料，不连接生产库或发送消息。 */
public final class PersonalCollectorLauncher {
    private PersonalCollectorLauncher() { }
    /** 读取来源配置并写出本地 HTML；输出目录必须不存在，避免覆盖历史预览。 */
    public static void main(String[] args) throws Exception {
        if(args.length<2||args.length>3)throw new IllegalArgumentException("Usage: PersonalCollectorLauncher sources.json output-directory [region-report.json]");
        Path sources=Path.of(args[0]),output=Path.of(args[1]).toAbsolutePath();
        ObjectMapper json=new ObjectMapper().findAndRegisterModules();
        RegionObservation observation=args.length==3?json.readValue(Files.readAllBytes(Path.of(args[2])),RegionObservation.class):null;
        SourceSpec[] specs=json.readValue(Files.readAllBytes(sources),SourceSpec[].class);
        String selectedCategories=System.getProperty("news.weeklyCategories","");
        Set<Category> weeklyCategories=EnumSet.allOf(Category.class);
        if (!selectedCategories.isBlank()) {
            if (System.getProperty("news.weeklyCutoff","").isBlank()) throw new IllegalArgumentException("Weekly category scope requires cutoff");
            weeklyCategories=EnumSet.noneOf(Category.class);
            for (String category : selectedCategories.split(",")) weeklyCategories.add(Category.valueOf(category));
        }
        // 扩展分类后保持显式来源预算，避免旧十源限制阻断正常采集。
        if(specs.length==0||specs.length>32)throw new IllegalArgumentException("Preview requires one to thirty-two sources");
        Files.createDirectory(output,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        DriverManagerDataSource data=new DriverManagerDataSource();
        data.setDriverClassName("org.h2.Driver");
        data.setUrl("jdbc:h2:mem:personal-preview-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc=new JdbcTemplate(data);
        try {
            Flyway.configure().dataSource(data).locations("classpath:db/migration").load().migrate();
            var tx=new TransactionTemplate(new DataSourceTransactionManager(data));
            var capacity=new CollectionCapacityRepository(jdbc,new CollectionCapacityProperties(250000,250000,100000,536870912));
            var control=new ControlCapacityRepository(jdbc,new ControlCapacityProperties(1000,1000,100000,100000));
            var registry=new RegistryCapacityRepository(jdbc,new RegistryCapacityProperties(1000,10000,1000,100000,10000));
            var snapshots=new GithubSnapshotRepository(jdbc,json,capacity,tx);
            var repository=new NewsRepository(jdbc,json,tx,snapshots,capacity,control,registry);
            var clock=Clock.systemUTC();var transport=new HttpFeedTransport();var client=new JsonSourceClient(transport,json);
            var limits=new TechSourceProperties("",30,10,0);
            var properties=new NewsProperties("preview-internal","preview-admin",true,ZoneId.of("Asia/Shanghai"),LocalTime.of(7,30));
            var collection=new CollectionService(repository,transport,new FeedParser(),properties,clock,
                    List.of(new GithubCollector(client,limits,snapshots,clock),new HackerNewsCollector(client,limits),new DevCollector(client,limits)));
            List<Map<String,Object>> outcomes=new ArrayList<>();
            for(SourceSpec source:specs) {
                repository.saveSource(source);
                if(!source.enabled() || !weeklyCategories.contains(source.category()))continue;
                // 单个来源失败仍生成其余来源的预览，并只记录稳定错误码。
                try {
                    CollectionResult result=collection.collect(source.id());
                    outcomes.add(Map.of("source",source.id(),"category",source.category().name(),"name",source.name(),"status",result.status(),"inserted",result.inserted(),"skipped",result.skipped()));
                } catch(NewsException exception) {outcomes.add(Map.of("source",source.id(),"category",source.category().name(),"name",source.name(),"status","FAILED","code",exception.code().name()));}
            }
            var regions=new RegionService(new RegionRepository(jdbc,json,control,tx,registry),repository,
                    new RegionProperties(observation==null?"":observation.nodeId(),"",50,true),clock);
            if(observation!=null) {
                // 只接收仍在有效期内的实际报告；更旧的报告不能被刷新时间冒充当前观测。
                regions.observe(observation);
                Map<String,List<String>> packs=new LinkedHashMap<>();
                for(SourceSpec source:specs)if(source.category()==Category.LOCAL && source.regionCode()!=null)
                    packs.computeIfAbsent(source.regionCode(),ignored->new ArrayList<>()).add(source.id());
                packs.forEach((code,ids)->regions.registerPack(new RegionSourcePack(code,1,
                        "CN-JS".equals(code)?"\u6c5f\u82cf\uff08\u7701\u7ea7\u8303\u56f4\uff09":code,ids,clock.instant())));
            }
            Instant cutoff=clock.instant();
            // 手动任务窗口由服务受理时冻结，不能在采集结束后向后滑动。
            String weeklyCutoff = System.getProperty("news.weeklyCutoff", "");
            Instant weeklyEnd = weeklyCutoff.isBlank() ? null : Instant.parse(weeklyCutoff);
            if (weeklyEnd != null && (weeklyEnd.isAfter(cutoff) || weeklyEnd.isBefore(cutoff.minus(Duration.ofDays(90))))) {
                throw new IllegalArgumentException("Invalid weekly cutoff");
            }
            var editions=new EditionService(repository,new DigestEngine(),properties,clock,regions);
            Edition edition=editions.preview(1,new PreviewRequest("personal-preview",cutoff));
            var renderer=new NewsDeliveryRenderer(json,new DeliveryProperties(false,"http://127.0.0.1:23250","",""));
            List<RenderedPart> parts=renderer.render(edition,Channel.EMAIL,RenderMode.FULL);
            for(RenderedPart part:parts)Files.writeString(output.resolve("daily-news-"+part.index()+".html"),part.content().html(),StandardOpenOption.CREATE_NEW);
            if (weeklyEnd != null || Boolean.getBoolean("news.illustrated")) {
                // 手动七天任务单独扩展候选池，DSH 分批合并后选择前二十，不修改普通日报快照。
                var articles = weeklyEnd == null ? repository.candidates(cutoff)
                        : repository.candidates(cutoff, weeklyEnd.minus(Duration.ofDays(7)), weeklyEnd);
                var candidates = weeklyEnd == null ? new DigestEngine().buildCandidates(edition, articles)
                        : new DigestEngine().buildWeeklyCandidates(edition, articles, weeklyEnd, 100);
                var input = new LinkedHashMap<>(new IllustratedReportCollector(transport).collect(candidates, articles, output));
                input.put("collection", outcomes);
                if (weeklyEnd != null) {
                    input.put("mode", "WEEKLY");
                    input.put("windowStart", weeklyEnd.minus(Duration.ofDays(7)).toString());
                    input.put("resultLimit", 20);
                }
                Files.writeString(output.resolve("report-input.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(input), StandardOpenOption.CREATE_NEW);
            }
            Map<String,Object> counts=new LinkedHashMap<>();
            for(Category category:Category.values()) {
                Section section=edition.sections().get(category);
                counts.put(category.name(),Map.of("headlines",section.headlineCount(),"details",section.detailCount(),"gaps",section.gaps()));
            }
            Map<String,Object> report=new LinkedHashMap<>();report.put("observedAt",cutoff.toString());report.put("sources",outcomes);
            report.put("sections",counts);report.put("parts",parts.size());report.put("region",edition.region().status());
            report.put("regionCode",Objects.toString(edition.region().regionCode(),""));
            report.put("realSources",true);report.put("productionDatabase",false);report.put("messagesSent",false);
            Files.writeString(output.resolve("preview-summary.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report),StandardOpenOption.CREATE_NEW);
            System.out.println("PERSONAL_PREVIEW "+json.writeValueAsString(report));
        } finally {jdbc.execute("SHUTDOWN");}
    }
}
