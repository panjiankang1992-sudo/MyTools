package com.yuyutian.mytools.drive.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.drive.model.DriveModels.IndexItem;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/** 仅允许回环 RC 和白名单目录列表操作的 rclone connector。 */
@Component
public class RcloneConnector {
    private static final Pattern REMOTE = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");
    private static final int MAX_BODY = 8 * 1024 * 1024;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String configuredUrl;
    private final String rcUser;
    private final String rcPassword;
    private URI baseUri;

    /** 创建 connector。 @param mapper JSON 映射器 @param configuredUrl RC 地址 */
    public RcloneConnector(ObjectMapper mapper, @Value("${drive.rclone-rc-url:http://127.0.0.1:5572}") String configuredUrl,
        @Value("${drive.rclone-rc-user:}") String rcUser, @Value("${drive.rclone-rc-password:}") String rcPassword) {
        this.mapper=mapper; this.configuredUrl=configuredUrl; this.rcUser=rcUser; this.rcPassword=rcPassword;
    }
    /** 验证 RC 只能绑定回环 HTTP。 */
    @PostConstruct public void validate() {
        URI candidate=URI.create(configuredUrl); String host=candidate.getHost();
        if(!"http".equals(candidate.getScheme()) || !("127.0.0.1".equals(host)||"localhost".equalsIgnoreCase(host))
            || candidate.getUserInfo()!=null || candidate.getQuery()!=null || candidate.getFragment()!=null
            || !(candidate.getPath().isEmpty()||"/".equals(candidate.getPath())))
            throw new IllegalStateException("rclone RC must use a loopback HTTP endpoint");
        baseUri=URI.create(configuredUrl.endsWith("/")?configuredUrl:configuredUrl+"/");
    }
    /** 列出服务端配置账户的一个目录。 @param remoteKey 远端键 @param path 相对路径 @return 索引项 */
    public List<IndexItem> list(String remoteKey,String path) {
        if(!REMOTE.matcher(remoteKey).matches()) throw new IllegalArgumentException("drive remote key is invalid");
        try {
            byte[] body=mapper.writeValueAsBytes(Map.of("fs",remoteKey+":","remote",path,
                "opt",Map.of("recurse",false,"showOrigIDs",true,"showHash",true)));
            HttpRequest.Builder builder=HttpRequest.newBuilder(baseUri.resolve("operations/list")).timeout(Duration.ofMinutes(2))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if(!rcUser.isBlank()||!rcPassword.isBlank()) builder.header("Authorization","Basic "+Base64.getEncoder()
                .encodeToString((rcUser+":"+rcPassword).getBytes(StandardCharsets.UTF_8)));
            HttpRequest request=builder.build();
            HttpResponse<byte[]> response=client.send(request,HttpResponse.BodyHandlers.ofByteArray());
            if(response.statusCode()<200||response.statusCode()>=300||response.body().length>MAX_BODY)
                throw new IllegalStateException("rclone list failed");
            JsonNode values=mapper.readTree(response.body()).path("list");
            if(!values.isArray()||values.size()>10000) throw new IllegalStateException("rclone list response is invalid");
            List<IndexItem> items=new ArrayList<>();
            for(JsonNode value:values) items.add(normalize(value,path));
            return List.copyOf(items);
        } catch(InterruptedException exception) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("rclone list interrupted",exception);
        } catch(java.io.IOException exception) { throw new IllegalStateException("rclone list failed",exception); }
    }
    private IndexItem normalize(JsonNode value,String parent) {
        String path=validPath(value.path("Path").asText());
        String name=value.path("Name").asText("").trim();
        if(name.isBlank()||name.length()>512||name.contains("/")||name.contains("\\"))
            throw new IllegalStateException("rclone item name is invalid");
        Instant modified=null; try { if(value.hasNonNull("ModTime")) modified=OffsetDateTime.parse(value.path("ModTime").asText()).toInstant(); }
        catch(DateTimeParseException ignored) { /* 远端时间缺失不阻断索引。 */ }
        String hash=value.path("Hashes").path("SHA-256").asText(null);
        if(hash!=null&&!hash.matches("^[a-fA-F0-9]{64}$")) hash=null;
        return new IndexItem(text(value,"ID",255),path,parent,name,text(value,"MimeType",255),
            Math.max(0,value.path("Size").asLong()),value.path("IsDir").asBoolean(),modified,hash);
    }
    private String validPath(String value) {
        if(value==null||value.isBlank()||value.length()>2048||value.startsWith("/")||value.contains(":")
            || Arrays.asList(value.split("/",-1)).contains("..")) throw new IllegalStateException("rclone item path is invalid");
        return value;
    }
    private String text(JsonNode value,String field,int max) {
        String result=value.path(field).asText(null); return result==null?null:result.substring(0,Math.min(max,result.length()));
    }
}
