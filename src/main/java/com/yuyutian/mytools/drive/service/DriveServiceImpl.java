package com.yuyutian.mytools.drive.service;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.drive.infrastructure.rclone.RcloneDirectorySize;
import com.yuyutian.mytools.drive.infrastructure.rclone.RcloneGateway;
import com.yuyutian.mytools.drive.infrastructure.rclone.RcloneItem;
import com.yuyutian.mytools.drive.mapper.DriveAccountMapper;
import com.yuyutian.mytools.drive.mapper.DriveItemIndexMapper;
import com.yuyutian.mytools.drive.model.DriveAccount;
import com.yuyutian.mytools.drive.model.DriveAccountView;
import com.yuyutian.mytools.drive.model.DriveDirectoryView;
import com.yuyutian.mytools.drive.model.DriveItemIndex;
import com.yuyutian.mytools.drive.model.DriveItemView;
import com.yuyutian.mytools.drive.model.DriveOpenTarget;
import com.yuyutian.mytools.utils.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 基于 rclone 元数据和本地搜索索引的网盘服务实现。
 */
@Service
@RequiredArgsConstructor
public class DriveServiceImpl implements DriveService {

    private static final Set<String> SORT_FIELDS = Set.of("modified", "name", "size");
    private static final Set<String> DIRECTIONS = Set.of("asc", "desc");

    private final DriveAccountMapper accountMapper;
    private final DriveItemIndexMapper itemMapper;
    private final RcloneGateway rcloneGateway;
    private final SnowflakeIdGenerator idGenerator;

    /** {@inheritDoc} */
    @Override
    public List<DriveAccountView> listDrives(Long userId) {
        return accountMapper.selectEnabledByUserId(userId).stream()
                .map(account -> new DriveAccountView(String.valueOf(account.getId()), account.getDisplayName(),
                        Boolean.TRUE.equals(account.getReadOnly()), account.getStatus()))
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public DriveDirectoryView listItems(Long userId, Long driveId, String itemId, String keyword,
                                        String sort, String direction) {
        DriveAccount account = requireAccount(userId, driveId);
        String normalizedSort = normalizeOption(sort, SORT_FIELDS, "modified");
        String normalizedDirection = normalizeOption(direction, DIRECTIONS, "desc");
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        if (normalizedKeyword.length() > 100 || normalizedKeyword.chars().anyMatch(value -> value < 32)) {
            throw new BusinessException(ErrorCode.DRIVE_004);
        }

        String path = resolvePath(driveId, itemId);
        List<DriveItemIndex> indexed;
        RcloneDirectorySize directorySize;
        if (normalizedKeyword.isBlank()) {
            indexed = rcloneGateway.list(account.getRemoteKey(), path).stream()
                    .map(item -> index(driveId, item))
                    .toList();
            // 页面统计仅汇总当前层级，避免对大型远端执行阻塞式递归扫描。
            directorySize = summarize(indexed);
        } else {
            indexed = itemMapper.search(driveId, normalizedKeyword, 100);
            directorySize = summarize(indexed);
        }
        List<DriveItemView> items = indexed.stream().sorted(comparator(normalizedSort, normalizedDirection))
                .map(this::view).toList();
        String name = path.isBlank() ? "Root" : path.substring(path.lastIndexOf('/') + 1);
        return new DriveDirectoryView(String.valueOf(driveId), itemId == null ? "" : itemId, name,
                directorySize.count(), directorySize.bytes(), items);
    }

    /** {@inheritDoc} */
    @Override
    public DriveOpenTarget resolveOpenTarget(Long userId, Long driveId, Long itemId) {
        DriveAccount account = requireAccount(userId, driveId);
        if (itemId == null || itemId <= 0) {
            throw new BusinessException(ErrorCode.DRIVE_004);
        }
        DriveItemIndex item = itemMapper.selectById(itemId, driveId);
        if (item == null || Boolean.TRUE.equals(item.getDirectory())) {
            throw new BusinessException(ErrorCode.DRIVE_002);
        }
        return new DriveOpenTarget(userId, driveId, itemId, account.getRemoteKey(), item.getRemotePath(),
                item.getDisplayName(), item.getMimeType(), item.getSizeBytes());
    }

    private DriveAccount requireAccount(Long userId, Long driveId) {
        if (driveId == null || driveId <= 0) {
            throw new BusinessException(ErrorCode.DRIVE_004);
        }
        DriveAccount account = accountMapper.selectOwned(driveId, userId);
        if (account == null) {
            throw new BusinessException(ErrorCode.DRIVE_001);
        }
        return account;
    }

    private String resolvePath(Long driveId, String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "";
        }
        long id;
        try {
            id = Long.parseLong(itemId);
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.DRIVE_004);
        }
        DriveItemIndex item = itemMapper.selectById(id, driveId);
        if (item == null || !Boolean.TRUE.equals(item.getDirectory())) {
            throw new BusinessException(ErrorCode.DRIVE_002);
        }
        return item.getRemotePath();
    }

    private DriveItemIndex index(Long driveId, RcloneItem source) {
        DriveItemIndex item = itemMapper.selectByPath(driveId, source.path());
        boolean insert = item == null;
        if (insert) {
            item = new DriveItemIndex();
            item.setId(idGenerator.nextId());
            item.setDriveId(driveId);
        }
        item.setRemotePath(source.path());
        int separator = source.path().lastIndexOf('/');
        item.setParentPath(separator < 0 ? "" : source.path().substring(0, separator));
        item.setDisplayName(source.name());
        item.setMimeType(source.mimeType());
        item.setExtension(extension(source.name()));
        item.setDirectory(source.directory());
        item.setSizeBytes(source.size());
        item.setModifiedAt(source.modifiedAt() == null ? null
                : LocalDateTime.ofInstant(source.modifiedAt().toInstant(), ZoneOffset.UTC));
        item.setEtag(source.id());
        item.setIndexedAt(LocalDateTime.now(ZoneOffset.UTC));
        item.setDeleted(false);
        if (insert) {
            try {
                itemMapper.insert(item);
            } catch (DuplicateKeyException ex) {
                DriveItemIndex existing = itemMapper.selectByPath(driveId, source.path());
                if (existing == null) {
                    throw ex;
                }
                item.setId(existing.getId());
                itemMapper.update(item);
            }
        } else {
            itemMapper.update(item);
        }
        return item;
    }

    private Comparator<DriveItemIndex> comparator(String sort, String direction) {
        Comparator<DriveItemIndex> value = switch (sort) {
            case "name" -> Comparator.comparing(item -> item.getDisplayName().toLowerCase(Locale.ROOT));
            case "size" -> Comparator.comparingLong(DriveItemIndex::getSizeBytes);
            default -> Comparator.comparing(DriveItemIndex::getModifiedAt,
                    Comparator.nullsLast(Comparator.naturalOrder()));
        };
        if ("desc".equals(direction)) {
            value = value.reversed();
        }
        return Comparator.comparing((DriveItemIndex item) -> !Boolean.TRUE.equals(item.getDirectory()))
                .thenComparing(value)
                .thenComparing(item -> item.getDisplayName().toLowerCase(Locale.ROOT));
    }

    private RcloneDirectorySize summarize(List<DriveItemIndex> items) {
        return new RcloneDirectorySize(items.size(),
                items.stream().filter(item -> !Boolean.TRUE.equals(item.getDirectory()))
                        .mapToLong(DriveItemIndex::getSizeBytes).sum());
    }

    private DriveItemView view(DriveItemIndex item) {
        return new DriveItemView(String.valueOf(item.getId()), item.getDisplayName(), kind(item),
                item.getMimeType(), item.getSizeBytes(), item.getModifiedAt());
    }

    private String kind(DriveItemIndex item) {
        if (Boolean.TRUE.equals(item.getDirectory())) {
            return "DIRECTORY";
        }
        String mime = item.getMimeType() == null ? "" : item.getMimeType().toLowerCase(Locale.ROOT);
        if (mime.startsWith("video/")) return "VIDEO";
        if (mime.startsWith("image/")) return "IMAGE";
        if (mime.startsWith("audio/")) return "AUDIO";
        if (mime.equals("text/html") || Set.of("html", "htm").contains(item.getExtension())) return "WEB";
        if (mime.startsWith("text/")) return "TEXT";
        return "OTHER";
    }

    private String extension(String name) {
        int separator = name.lastIndexOf('.');
        if (separator < 0 || separator == name.length() - 1) {
            return "";
        }
        return name.substring(separator + 1).toLowerCase(Locale.ROOT).substring(
                0, Math.min(32, name.length() - separator - 1));
    }

    private String normalizeOption(String value, Set<String> supported, String fallback) {
        String normalized = value == null ? fallback : value.trim().toLowerCase(Locale.ROOT);
        if (!supported.contains(normalized)) {
            throw new BusinessException(ErrorCode.DRIVE_004);
        }
        return normalized;
    }
}
