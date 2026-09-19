package com.yuyutian.mytools.reader.service.adaptation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.config.ReaderAdaptationDispatchProperties;
import com.yuyutian.mytools.reader.config.ReaderProperties;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationDispatchModels.SchedulerView;
import com.yuyutian.mytools.reader.utils.adaptation.BoundedByteArraySubscriber;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** 固定私网调度端点的有界客户端，禁止重定向和事务内网络调用。 */
@Component
public class HttpAdaptationSchedulerGateway implements AdaptationSchedulerGateway {
    private static final Set<String> STATUSES = Set.of("CREATED", "QUEUED", "RUNNING", "WAITING_CHILDREN", "CANCELLING",
            "SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED");
    private final HttpClient client;
    private final URI base;
    private final String token;
    private final ObjectMapper mapper;
    private final int timeoutSeconds;

    /** 从 Reader 的部署配置读取私网地址与业务服务凭据，不接收客户端覆盖。 */
    @Autowired
    public HttpAdaptationSchedulerGateway(ReaderProperties reader, ReaderAdaptationDispatchProperties properties, ObjectMapper mapper,
                                          @Value("${reader.scheduler-token:}") String token) {
        this(reader.schedulerUrl(), properties.requestTimeoutSeconds(), mapper, token);
    }

    /** 允许本机 HTTP 夹具注入受控地址；不把地址和令牌输出到诊断。 */
    public HttpAdaptationSchedulerGateway(String baseUrl, int timeoutSeconds, ObjectMapper mapper, String token) {
        URI parsed = URI.create(baseUrl);
        if (parsed.getHost() == null || !Set.of("http", "https").contains(parsed.getScheme()) || parsed.getUserInfo() != null
                || parsed.getQuery() != null || parsed.getFragment() != null
                || (parsed.getPath() != null && !parsed.getPath().isEmpty() && !"/".equals(parsed.getPath()))
                || timeoutSeconds < 1 || timeoutSeconds > 30) {
            throw new IllegalArgumentException("Invalid adaptation scheduler configuration");
        }
        this.base = parsed;
        this.timeoutSeconds = timeoutSeconds;
        this.token = token;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.min(3, timeoutSeconds)))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** 只发送固定路径中的业务 UUID，不包含请求正文。 */
    @Override
    public SchedulerView submit(UUID adaptationId) {
        return invoke(adaptationId, "POST", "");
    }

    /** 查询未回绑任务的持久调度身份。 */
    @Override
    public SchedulerView find(UUID adaptationId) {
        return invoke(adaptationId, "GET", "");
    }

    /** 按业务键取消，包括尚未到达 Scheduler 的迟到请求。 */
    @Override
    public SchedulerView cancel(UUID adaptationId) {
        return invoke(adaptationId, "POST", "/cancel");
    }

    private SchedulerView invoke(UUID adaptationId, String method, String suffix) {
        if (TransactionSynchronizationManager.isActualTransactionActive() || token == null || token.isBlank()) {
            throw new AdaptationSchedulerException(false);
        }
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            URI target = base.resolve("/api/v1/task-instances/reader-adaptations/" + adaptationId + suffix);
            var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("X-Task-Service-Id", "reader-service").header("X-Task-Business-Token", token)
                    .header("Accept", "application/json").header("Accept-Encoding", "identity")
                    .method(method, HttpRequest.BodyPublishers.noBody()).build();
            pending = client.sendAsync(request, ignored -> new BoundedByteArraySubscriber(65536));
            var response = pending.get(timeoutSeconds, TimeUnit.SECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AdaptationSchedulerException(response.statusCode() == 429 || response.statusCode() >= 500);
            }
            if (!response.headers().firstValue("Content-Encoding").orElse("identity").equalsIgnoreCase("identity")
                    || !response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
                throw new AdaptationSchedulerException(false);
            }
            var node = mapper.readTree(response.body());
            if (node == null || !node.isObject() || node.size() != 4 || !node.path("adaptationId").isTextual()
                    || !adaptationId.toString().equals(node.path("adaptationId").textValue())
                    || !node.path("cancellationRecorded").isBoolean() || !node.has("taskInstanceId") || !node.has("taskStatus")
                    || (node.get("taskInstanceId").isNull() != node.get("taskStatus").isNull())) {
                throw new AdaptationSchedulerException(false);
            }
            UUID task = null;
            String status = null;
            if (!node.get("taskInstanceId").isNull()) {
                if (!node.get("taskInstanceId").isTextual() || !node.get("taskStatus").isTextual()
                        || !STATUSES.contains(node.get("taskStatus").textValue())) {
                    throw new AdaptationSchedulerException(false);
                }
                task = UUID.fromString(node.get("taskInstanceId").textValue());
                if (!task.toString().equals(node.get("taskInstanceId").textValue())) {
                    throw new AdaptationSchedulerException(false);
                }
                status = node.get("taskStatus").textValue();
            }
            if (method.equals("POST") && suffix.isEmpty() && task == null) {
                throw new AdaptationSchedulerException(false);
            }
            return new SchedulerView(adaptationId, task, node.get("cancellationRecorded").booleanValue(), status);
        } catch (AdaptationSchedulerException exception) {
            throw exception;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            // 非法 JSON 或身份不能通过重试变为可信响应，也不向上游透传解析片段。
            throw new AdaptationSchedulerException(false);
        } catch (ExecutionException exception) {
            throw new AdaptationSchedulerException(!(exception.getCause() instanceof BoundedByteArraySubscriber.LimitExceededException));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AdaptationSchedulerException(true);
        } catch (Exception exception) {
            // 超时及响应丢失结果不确定，只能以原业务键重试；不保留外部异常链。
            throw new AdaptationSchedulerException(true);
        } finally {
            if (pending != null && !pending.isDone()) {
                pending.cancel(true);
            }
        }
    }
}
