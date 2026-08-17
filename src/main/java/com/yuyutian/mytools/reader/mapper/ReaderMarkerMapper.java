package com.yuyutian.mytools.reader.mapper;

import com.yuyutian.mytools.reader.model.ReaderMarker;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

import java.util.List;

/**
 * 阅读标记数据访问接口。
 */
@Mapper
public interface ReaderMarkerMapper {
    /** 删除用户全部阅读标记。 @param userId 用户ID @return 删除行数 */
    @Delete("DELETE FROM t_reader_marker WHERE user_id = #{userId}")
    int deleteByUserId(Long userId);

    /**
     * 统计用户阅读标记数量。
     *
     * @param userId 用户ID
     * @return 标记和墓碑总数
     */
    @Select("SELECT COUNT(*) FROM t_reader_marker WHERE user_id = #{userId}")
    long countByUserId(Long userId);

    /**
     * 查询用户全部阅读标记及删除墓碑。
     *
     * @param userId 用户ID
     * @return 阅读标记列表
     */
    @Select("SELECT user_id AS userId, marker_id AS markerId, kind, book_id AS bookId, "
            + "chapter_title AS chapterTitle, locator, note, created_at AS createdAt, "
            + "client_updated_at AS clientUpdatedAt, server_updated_at AS serverUpdatedAt, deleted, revision "
            + "FROM t_reader_marker WHERE user_id = #{userId} ORDER BY server_updated_at LIMIT 10000")
    List<ReaderMarker> findAllByUserId(Long userId);

    /**
     * 查询单个阅读标记。
     *
     * @param userId 用户ID
     * @param markerId 标记ID
     * @return 阅读标记
     */
    @Select("SELECT user_id AS userId, marker_id AS markerId, kind, book_id AS bookId, "
            + "chapter_title AS chapterTitle, locator, note, created_at AS createdAt, "
            + "client_updated_at AS clientUpdatedAt, server_updated_at AS serverUpdatedAt, deleted, revision "
            + "FROM t_reader_marker WHERE user_id = #{userId} AND marker_id = #{markerId}")
    ReaderMarker findById(@Param("userId") Long userId, @Param("markerId") String markerId);

    /**
     * 新增阅读标记。
     *
     * @param marker 阅读标记
     * @return 影响行数
     */
    @Insert("INSERT INTO t_reader_marker (user_id, marker_id, kind, book_id, chapter_title, locator, note, "
            + "created_at, client_updated_at, server_updated_at, deleted, revision) VALUES (#{userId}, "
            + "#{markerId}, #{kind}, #{bookId}, #{chapterTitle}, #{locator}, #{note}, #{createdAt}, "
            + "#{clientUpdatedAt}, #{serverUpdatedAt}, #{deleted}, 1)")
    int insert(ReaderMarker marker);

    /**
     * 使用乐观锁更新阅读标记。
     *
     * @param marker 阅读标记
     * @param expectedRevision 客户端基准版本
     * @return 影响行数
     */
    @Update("UPDATE t_reader_marker SET kind = #{marker.kind}, book_id = #{marker.bookId}, "
            + "chapter_title = #{marker.chapterTitle}, locator = #{marker.locator}, note = #{marker.note}, "
            + "created_at = #{marker.createdAt}, client_updated_at = #{marker.clientUpdatedAt}, "
            + "server_updated_at = #{marker.serverUpdatedAt}, deleted = #{marker.deleted}, revision = revision + 1 "
            + "WHERE user_id = #{marker.userId} AND marker_id = #{marker.markerId} AND revision = #{expectedRevision}")
    int updateIfRevisionMatches(@Param("marker") ReaderMarker marker,
                                @Param("expectedRevision") Long expectedRevision);
}
