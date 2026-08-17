package com.yuyutian.mytools.reader.mapper;

import com.yuyutian.mytools.reader.model.ReadingProgress;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

import java.util.List;

/**
 * 阅读进度数据访问接口。
 */
@Mapper
public interface ReadingProgressMapper {
    /**
     * 删除用户全部阅读进度。
     *
     * @param userId 用户ID
     * @return 删除行数
     */
    @Delete("DELETE FROM t_reading_progress WHERE user_id = #{userId}")
    int deleteByUserId(Long userId);

    /**
     * 统计用户阅读进度。
     *
     * @param userId 用户ID
     * @return 记录数
     */
    @Select("SELECT COUNT(*) FROM t_reading_progress WHERE user_id = #{userId}")
    long countByUserId(Long userId);

    /**
     * 查询用户全部阅读进度。
     *
     * @param userId 用户ID
     * @return 阅读进度列表
     */
    @Select("SELECT user_id AS userId, book_id AS bookId, chapter_title AS chapterTitle, locator, percentage, "
            + "client_updated_at AS clientUpdatedAt, server_updated_at AS serverUpdatedAt, deleted, revision "
            + "FROM t_reading_progress WHERE user_id = #{userId} ORDER BY server_updated_at DESC")
    List<ReadingProgress> findAllByUserId(Long userId);

    /**
     * 查询单本书的阅读进度。
     *
     * @param userId 用户ID
     * @param bookId 图书稳定标识
     * @return 阅读进度
     */
    @Select("SELECT user_id AS userId, book_id AS bookId, chapter_title AS chapterTitle, locator, percentage, "
            + "client_updated_at AS clientUpdatedAt, server_updated_at AS serverUpdatedAt, deleted, revision "
            + "FROM t_reading_progress WHERE user_id = #{userId} AND book_id = #{bookId}")
    ReadingProgress findByUserIdAndBookId(@Param("userId") Long userId, @Param("bookId") String bookId);

    /**
     * 新增首次阅读进度。
     *
     * @param progress 阅读进度
     * @return 影响行数
     */
    @Insert("INSERT INTO t_reading_progress (user_id, book_id, chapter_title, locator, percentage, "
            + "client_updated_at, server_updated_at, deleted, revision) VALUES (#{userId}, #{bookId}, #{chapterTitle}, "
            + "#{locator}, #{percentage}, #{clientUpdatedAt}, #{serverUpdatedAt}, #{deleted}, 1)")
    int insert(ReadingProgress progress);

    /**
     * 使用乐观锁更新阅读进度。
     *
     * @param progress 阅读进度
     * @param expectedRevision 客户端基准版本
     * @return 影响行数
     */
    @Update("UPDATE t_reading_progress SET chapter_title = #{progress.chapterTitle}, locator = #{progress.locator}, "
            + "percentage = #{progress.percentage}, client_updated_at = #{progress.clientUpdatedAt}, "
            + "server_updated_at = #{progress.serverUpdatedAt}, deleted = #{progress.deleted}, revision = revision + 1 "
            + "WHERE user_id = #{progress.userId} AND book_id = #{progress.bookId} AND revision = #{expectedRevision}")
    int updateIfRevisionMatches(@Param("progress") ReadingProgress progress,
                                @Param("expectedRevision") Long expectedRevision);
}
