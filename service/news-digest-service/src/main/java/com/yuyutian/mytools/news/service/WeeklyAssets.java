package com.yuyutian.mytools.news.service;

import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.config.WeeklyProperties;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/** 私有新闻原图存储，只接收有界图片字节，不代理客户端网址。 */
@Component
public class WeeklyAssets {
    private static final int MAX_BYTES = 1_048_576;
    private final Path root;

    /** 从服务端固定配置建立隔离目录。 */
    public WeeklyAssets(WeeklyProperties properties) { root=Path.of(properties.assetDirectory()).toAbsolutePath().normalize(); }

    /** 原图响应不包含服务器路径或公共地址。 */
    public record Asset(String assetId, String mime, String base64) { }

    /** 校验原图并按内容哈希原子落盘，相同图片重复上传不增加文件。 */
    public String put(UUID job, byte[] bytes) {
        inspect(bytes);
        String id;
        try { id=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
        Path directory=root.resolve(job.toString()), target=directory.resolve(id);
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(target)) throw new IOException("Invalid asset location");
            if (Files.exists(target)) return id;
            // 未发布图片也受每任务数量限制，失败重试不会无限占用磁盘。
            try (var entries=Files.list(directory)) {
                if (entries.limit(401).count()>=400) throw new NewsException(ErrorCode.NEWS_035);
            }
            Path temporary=Files.createTempFile(directory,"upload-",".tmp");
            try {
                Files.write(temporary,bytes);
                Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temporary); }
            return id;
        } catch (IOException exception) { throw new NewsException(ErrorCode.NEWS_035); }
    }

    /** 只读取受控任务目录下的哈希文件，业务层负责报告引用与所有者校验。 */
    public Asset read(UUID job, String id) {
        if (!id.matches("[a-f0-9]{64}")) throw new NewsException(ErrorCode.NEWS_003);
        Path directory=root.resolve(job.toString()), path=directory.resolve(id);
        try {
            if (Files.isSymbolicLink(directory) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path)>MAX_BYTES) throw new NewsException(ErrorCode.NEWS_003);
            byte[] bytes=Files.readAllBytes(path);
            return new Asset(id,inspect(bytes),Base64.getEncoder().encodeToString(bytes));
        } catch (IOException exception) { throw new NewsException(ErrorCode.NEWS_003); }
    }

    /** 清理已过保留期的任务目录，不跟随符号链接或递归其他路径。 */
    public void remove(UUID job) {
        Path directory=root.resolve(job.toString());
        try {
            if (!Files.exists(directory,LinkOption.NOFOLLOW_LINKS)) return;
            if (!Files.isSymbolicLink(directory)) {
                try (var entries=Files.list(directory)) {
                    for (Path path : entries.toList()) Files.delete(path);
                }
            }
            Files.delete(directory);
        } catch (IOException exception) { throw new NewsException(ErrorCode.NEWS_035); }
    }

    private String inspect(byte[] bytes) {
        if (bytes==null || bytes.length<16 || bytes.length>MAX_BYTES) throw new NewsException(ErrorCode.NEWS_033);
        try (var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new NewsException(ErrorCode.NEWS_033);
            var reader=readers.next();
            try {
                reader.setInput(input,true,true);
                String format=reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                // 仅接受可安全显示的静态格式，先检查尺寸再解码，拒绝 SVG 和像素炸弹。
                if (!(format.equals("png") || format.equals("jpeg")) || width<1 || height<1
                        || width>4096 || height>4096 || (long)width*height>8_000_000 || reader.read(0)==null)
                    throw new NewsException(ErrorCode.NEWS_033);
                return format.equals("png")?"image/png":"image/jpeg";
            } finally { reader.dispose(); }
        } catch (IOException | RuntimeException exception) { throw new NewsException(ErrorCode.NEWS_033); }
    }
}
