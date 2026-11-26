package com.yuyutian.mytools.news.controller;

import com.yuyutian.mytools.news.Model.DeliveryModels.*;
import com.yuyutian.mytools.news.config.DeliveryProperties;
import com.yuyutian.mytools.news.service.NewsDispatchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

/** 目标与投递操作始终使用 Gateway 注入的所有者。 */
@RestController
@Validated
@RequestMapping("/internal/v1/news")
public class NewsDeliveryController {
    private final NewsDispatchService service;
    private final DeliveryProperties properties;
    /** 注入业务服务和实际发送开关。 */
    public NewsDeliveryController(NewsDispatchService service,DeliveryProperties properties){this.service=service;this.properties=properties;}
    /** 返回渠道能力，不把 QQ 平台限制隐藏为配置缺失。 */
    @GetMapping("/delivery-capabilities")
    public Capabilities capabilities(){return new Capabilities(properties.enabled(),List.of("EMAIL","FEISHU"),"PROACTIVE_PUSH_UNAVAILABLE","ADMIN_VERIFIED",5);}
    /** 列出本人已验证目标。 */
    @GetMapping("/destinations")
    public List<Destination> destinations(@RequestParam @Min(1)long ownerId){return service.destinations(ownerId);}
    /** 获取个人接收配置。 */
    @GetMapping("/delivery-targets")
    public Targets targets(@RequestParam @Min(1)long ownerId){return service.targets(ownerId);}
    /** 乐观锁替换目标，只对下一次每日运行生效。 */
    @PutMapping("/delivery-targets")
    public Targets change(@RequestParam @Min(1)long ownerId,@Valid @RequestBody TargetChange request){return service.changeTargets(ownerId,request);}
    /** 获取本人投递历史。 */
    @GetMapping("/deliveries")
    public List<BatchView> history(@RequestParam @Min(1)long ownerId){return service.history(ownerId);}
    /** 获取本人投递详情。 */
    @GetMapping("/deliveries/{id}")
    public BatchView get(@RequestParam @Min(1)long ownerId,@PathVariable UUID id){return service.get(ownerId,id);}
    /** 请求停止批次。 */
    @PostMapping("/deliveries/{id}/cancel")
    public BatchView cancel(@RequestParam @Min(1)long ownerId,@PathVariable UUID id){return service.cancel(ownerId,id);}
    /** 显式重试一个失败片段。 */
    @PostMapping("/deliveries/{id}/parts/{part}/retry")
    public BatchView retry(@RequestParam @Min(1)long ownerId,@PathVariable UUID id,@PathVariable UUID part,@Valid @RequestBody Retry request){return service.retry(ownerId,id,part,request);}
    /** 修复目标或配置后恢复被拒绝的原片段。 */
    @PostMapping("/deliveries/{id}/parts/{part}/redrive")
    public BatchView redrive(@RequestParam @Min(1)long ownerId,@PathVariable UUID id,@PathVariable UUID part){service.redrive(ownerId,id,part);return service.get(ownerId,id);}
    /** 区分发送开关、可用渠道和绑定流程。 */
    public record Capabilities(boolean enabled,List<String> supportedChannels,String qqStatus,String bindingMode,int targetLimit){}
}
