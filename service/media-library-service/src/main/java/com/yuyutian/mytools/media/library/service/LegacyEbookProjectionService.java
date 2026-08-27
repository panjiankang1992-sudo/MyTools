package com.yuyutian.mytools.media.library.service;

import com.yuyutian.mytools.media.library.config.MediaLibraryConfiguration.LegacyContentDatabase;
import com.yuyutian.mytools.media.library.model.MediaModels.EbookPage;
import com.yuyutian.mytools.media.library.model.MediaModels.MediaView;
import com.yuyutian.mytools.media.library.repository.MediaRepository;
import org.springframework.stereotype.Service;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

/** 按旧系统 EBOOK 目录边界投影已迁移电子书。 */
@Service
public class LegacyEbookProjectionService {
    private static final String EXTENSIONS = "'txt','epub','pdf','mobi','azw3','cbz','cbr'";
    private final MediaRepository repository;
    private final LegacyContentDatabase database;

    /** 创建投影服务。 @param repository 媒体仓储 @param database 旧库只读配置 */
    public LegacyEbookProjectionService(MediaRepository repository, LegacyContentDatabase database) {
        this.repository = repository;
        this.database = database;
    }

    /** 查询当前所有者电子书目录。 @param ownerId 所有者 @param page 页码 @param pageSize 页大小 @param keyword 关键字 @return 页面 */
    public EbookPage list(long ownerId, int page, int pageSize, String keyword) {
        if (ownerId <= 0 || page < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("ebook query is invalid");
        String trimmed = keyword == null ? "" : keyword.trim();
        String query = trimmed.substring(0, Math.min(100, trimmed.length()));
        String url = "jdbc:mysql://" + database.host() + ":" + database.port() + "/" + database.database()
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
        try (var connection = DriverManager.getConnection(url, database.username(), database.password())) {
            connection.setReadOnly(true);
            String root;
            try (var statement = connection.prepareStatement("SELECT directory_path FROM local_directory WHERE directory_type='EBOOK' ORDER BY id LIMIT 1"); var result = statement.executeQuery()) {
                if (!result.next()) return new EbookPage(List.of(), 0, page, pageSize);
                root = result.getString(1).replaceAll("/+$", "");
            }
            String condition = "deleted=0 AND (file_path=? OR file_path LIKE CONCAT(?, '/%')) AND LOWER(SUBSTRING_INDEX(file_path,'.',-1)) IN (" + EXTENSIONS + ") AND (?='' OR LOWER(filename) LIKE CONCAT('%',LOWER(?),'%'))";
            long total;
            try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM local_file WHERE " + condition)) {
                bind(statement, root, query);
                try (var result = statement.executeQuery()) { result.next(); total = result.getLong(1); }
            }
            List<MediaView> items = new ArrayList<>();
            try (var statement = connection.prepareStatement("SELECT id FROM local_file WHERE " + condition + " ORDER BY id LIMIT ? OFFSET ?")) {
                bind(statement, root, query); statement.setInt(5, pageSize); statement.setLong(6, (long) (page - 1) * pageSize);
                try (var result = statement.executeQuery()) { while (result.next()) repository.viewByLegacyFile(ownerId, result.getLong(1)).ifPresent(items::add); }
            }
            return new EbookPage(List.copyOf(items), total, page, pageSize);
        } catch (Exception exception) {
            throw new IllegalStateException("legacy ebook projection failed", exception);
        }
    }

    private void bind(java.sql.PreparedStatement statement, String root, String query) throws java.sql.SQLException {
        statement.setString(1, root); statement.setString(2, root); statement.setString(3, query); statement.setString(4, query);
    }
}
