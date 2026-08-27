package com.yuyutian.mytools.reader.mapper;

import com.yuyutian.mytools.reader.model.BookSourceSearchCache;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户书源搜索结果缓存数据访问接口。
 */
@Mapper
public interface BookSourceSearchCacheMapper {

    /**
     * 查询仍有效且书源版本一致的缓存。
     *
     * @param userId 用户ID
     * @param normalizedKeyword 规范化关键词
     * @param queryMode 查询方式
     * @param page 页码
     * @param now 当前时间戳
     * @return 当前查询下仍有效的各书源缓存
     */
    @Select("SELECT user_id AS userId, normalized_keyword AS normalizedKeyword, query_mode AS queryMode, "
            + "source_id AS sourceId, page, source_revision AS sourceRevision, cache_status AS cacheStatus, "
            + "results_json AS resultsJson, result_count AS resultCount, created_at AS createdAt, "
            + "expires_at AS expiresAt "
            + "FROM t_book_source_search_cache WHERE user_id = #{userId} "
            + "AND normalized_keyword = #{normalizedKeyword} AND query_mode = #{queryMode} "
            + "AND page = #{page} AND expires_at > #{now}")
    List<BookSourceSearchCache> findValidForSearch(@Param("userId") Long userId,
                                                   @Param("normalizedKeyword") String normalizedKeyword,
                                                   @Param("queryMode") String queryMode,
                                                   @Param("page") int page,
                                                   @Param("now") long now);

    /**
     * 写入或刷新搜索缓存，空结果同样写入。
     *
     * @param cache 缓存记录
     * @return 影响行数
     */
    @Insert("INSERT INTO t_book_source_search_cache (user_id, normalized_keyword, query_mode, source_id, page, "
            + "source_revision, cache_status, results_json, result_count, created_at, expires_at) VALUES (#{userId}, "
            + "#{normalizedKeyword}, #{queryMode}, #{sourceId}, #{page}, #{sourceRevision}, #{cacheStatus}, "
            + "#{resultsJson}, #{resultCount}, #{createdAt}, #{expiresAt}) ON DUPLICATE KEY UPDATE "
            + "source_revision = VALUES(source_revision), cache_status = VALUES(cache_status), "
            + "results_json = VALUES(results_json), "
            + "result_count = VALUES(result_count), created_at = VALUES(created_at), expires_at = VALUES(expires_at)")
    int upsert(BookSourceSearchCache cache);

    /**
     * 删除已过期缓存。
     *
     * @param now 当前时间戳
     * @return 删除行数
     */
    @Delete("DELETE FROM t_book_source_search_cache WHERE expires_at <= #{now}")
    int deleteExpired(long now);
}
