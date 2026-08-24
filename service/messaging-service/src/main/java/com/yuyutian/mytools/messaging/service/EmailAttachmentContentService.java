package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.config.EmailIngressProperties;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按持久化 IMAP 引用重新读取邮件附件内容。
 */
@Service
public class EmailAttachmentContentService {
    private static final Pattern REFERENCE = Pattern.compile(
            "^imap:([A-Za-z0-9_]{1,64}):([0-9]+):([0-9]+):([1-9][0-9]*)$");
    private final EmailIngressProperties properties;

    /**
     * 创建邮件附件内容服务。
     *
     * @param properties IMAP 账户配置
     */
    public EmailAttachmentContentService(EmailIngressProperties properties) {
        this.properties = properties;
    }

    /**
     * 判断引用是否属于当前启用的邮件账户。
     *
     * @param providerFileId Provider 文件引用
     * @param accountKey 账户逻辑键
     * @return 是否支持
     */
    public boolean supports(String providerFileId, String accountKey) {
        Matcher matcher = providerFileId == null ? null : REFERENCE.matcher(providerFileId);
        return properties.enabled() && matcher != null && matcher.matches()
                && matcher.group(1).equals(accountKey) && matcher.group(1).equals(properties.accountKey());
    }

    /**
     * 流式读取一个 IMAP 附件，不改变服务器邮件标志。
     *
     * @param providerFileId Provider 文件引用
     * @param accountKey 账户逻辑键
     * @param output 输出流
     * @param maximumBytes 最大字节数
     */
    public void stream(String providerFileId, String accountKey, OutputStream output, long maximumBytes) {
        Matcher matcher = REFERENCE.matcher(providerFileId == null ? "" : providerFileId);
        if (!supports(providerFileId, accountKey) || maximumBytes < 1) {
            throw new AttachmentDownloadInvalidException();
        }
        long uidValidity = Long.parseLong(matcher.group(2));
        long uid = Long.parseLong(matcher.group(3));
        int ordinal = Integer.parseInt(matcher.group(4));
        Store store = null;
        Folder folder = null;
        try {
            Properties mailProperties = new Properties();
            mailProperties.setProperty("mail.store.protocol", properties.ssl() ? "imaps" : "imap");
            Store currentStore = Session.getInstance(mailProperties).getStore(properties.ssl() ? "imaps" : "imap");
            store = currentStore;
            currentStore.connect(properties.host(), properties.port(), properties.username(), properties.password());
            folder = currentStore.getFolder(properties.mailbox());
            folder.open(Folder.READ_ONLY);
            if (!(folder instanceof UIDFolder uidFolder) || uidFolder.getUIDValidity() != uidValidity) {
                throw new AttachmentDownloadInvalidException();
            }
            Message message = uidFolder.getMessageByUID(uid);
            if (message == null) {
                throw new AttachmentDownloadInvalidException();
            }
            Part attachment = findAttachment(message, ordinal, new int[]{0});
            if (attachment == null) {
                throw new AttachmentDownloadInvalidException();
            }
            try (InputStream input = attachment.getInputStream()) {
                copy(input, output, maximumBytes);
            }
        } catch (AttachmentDownloadInvalidException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new EmailIngressException(exception);
        } finally {
            close(folder, store);
        }
    }

    private Part findAttachment(Part part, int target, int[] current) throws Exception {
        boolean attachment = Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null;
        if (attachment) {
            current[0]++;
            return current[0] == target ? part : null;
        }
        Object content = part.getContent();
        if (content instanceof Multipart multipart) {
            for (int index = 0; index < multipart.getCount(); index++) {
                Part found = findAttachment(multipart.getBodyPart(index), target, current);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        return null;
    }

    private void copy(InputStream input, OutputStream output, long maximumBytes) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        long count = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            count += read;
            if (count > maximumBytes) {
                throw new AttachmentDownloadInvalidException();
            }
            output.write(buffer, 0, read);
        }
    }

    private void close(Folder folder, Store store) {
        try {
            if (folder != null && folder.isOpen()) {
                folder.close(false);
            }
        } catch (Exception ignored) {
            // 只读邮箱关闭失败不覆盖原始结果。
        }
        try {
            if (store != null && store.isConnected()) {
                store.close();
            }
        } catch (Exception ignored) {
            // 连接关闭失败不覆盖原始结果。
        }
    }
}
