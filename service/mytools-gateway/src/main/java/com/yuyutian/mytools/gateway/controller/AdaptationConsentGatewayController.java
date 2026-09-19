package com.yuyutian.mytools.gateway.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.gateway.config.ChapterAdaptationGatewayProperties;
import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.ChapterAdaptationGatewayModels.*;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.ChapterAdaptationGatewayClient;
import com.yuyutian.mytools.gateway.service.GatewayBadRequestException;
import com.yuyutian.mytools.gateway.service.GatewayRouteDisabledException;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/** 独立账户入口始终认证；历史灰度关闭后仍能读取和撤销本人的授权。 */
@RestController
@RequestMapping("/api/app/v1/features/reader-adaptation")
public class AdaptationConsentGatewayController {
    private final GatewayProperties gateway;
    private final ChapterAdaptationGatewayProperties switches;
    private final ChapterAdaptationGatewayClient client;
    private final ObjectMapper mapper;

    /** 固定五个同意字段并禁止 JSON 类型强制转换。 */
    public AdaptationConsentGatewayController(GatewayProperties gateway, ChapterAdaptationGatewayProperties switches,
                                              ChapterAdaptationGatewayClient client, ObjectMapper mapper) {
        this.gateway = gateway; this.switches = switches; this.client = client;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    /** 未进入灰度的登录用户只会获得关闭能力，不会因此取得改编创建权限。 */
    @GetMapping
    public Features features(HttpServletRequest request) {
        long owner = owner(request, Set.of()); return mask(owner, client.features(owner, correlation(request)));
    }

    /** 明确勾选、精确告知和当前修订全部满足后才转发同意。 */
    @PostMapping(value = "/consent", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Features accept(@RequestBody String body, HttpServletRequest request) {
        long owner = owner(request, Set.of());
        if (!switches.readEnabled() || !gateway.readerTenantAllowed(owner)) throw new GatewayRouteDisabledException();
        ConsentAccept input;
        try {
            if (body.length() > 4096) throw invalid();
            var node = mapper.readTree(body);
            if (node == null || !node.isObject() || node.size() != 5 || !node.path("accepted").isBoolean()
                    || !node.path("accepted").booleanValue() || !node.path("rightsAttested").isBoolean()
                    || !node.path("rightsAttested").booleanValue() || !node.path("expectedConsentRevision").isIntegralNumber()
                    || !node.path("expectedConsentRevision").canConvertToLong()
                    || !node.path("disclosureVersion").isTextual() || !node.path("disclosureSha256").isTextual()) throw invalid();
            input = mapper.treeToValue(node, ConsentAccept.class);
            if (!input.disclosureVersion().matches("[A-Za-z0-9_.:-]{1,64}") || !input.disclosureSha256().matches("[a-f0-9]{64}")) throw invalid();
            revision(input.expectedConsentRevision());
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw invalid(); }
        return mask(owner, client.acceptConsent(owner, input, correlation(request)));
    }

    /** 关闭改编或移出灰度不能阻止用户撤销已有授权。 */
    @DeleteMapping("/consent")
    public Features revoke(@RequestParam String expectedConsentRevision, @RequestBody(required = false) String body, HttpServletRequest request) {
        long owner = owner(request, Set.of("expectedConsentRevision"));
        if (body != null && !body.isBlank()) throw invalid();
        try {
            if (!expectedConsentRevision.matches("0|[1-9][0-9]{0,15}")) throw invalid();
            long revision = Long.parseLong(expectedConsentRevision); revision(revision);
            return mask(owner, client.revokeConsent(owner, revision, correlation(request)));
        } catch (NumberFormatException exception) { throw invalid(); }
    }

    private Features mask(long owner, Features value) {
        boolean read = switches.readEnabled() && gateway.readerTenantAllowed(owner) && value.readEnabled();
        return new Features(read, read && switches.createEnabled() && value.createEnabled(), value.consentStatus(),
                value.consentRevision(), value.hasActiveConsent(), value.acceptedAt(), value.reasonCode(), value.disclosure());
    }
    private static long owner(HttpServletRequest request, Set<String> fields) {
        Object value = request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE);
        if (!(value instanceof GatewayPrincipal principal) || principal.userId() < 1) throw new GatewayRouteDisabledException();
        if (!fields.containsAll(request.getParameterMap().keySet()) || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) throw invalid();
        return principal.userId();
    }
    private static void revision(long revision) { if (revision < 0 || revision > 9007199254740991L) throw invalid(); }
    private static String correlation(HttpServletRequest request) { return (String) request.getAttribute(GatewayRequestFilter.CORRELATION_ATTRIBUTE); }
    private static GatewayBadRequestException invalid() { return new GatewayBadRequestException(); }
}
