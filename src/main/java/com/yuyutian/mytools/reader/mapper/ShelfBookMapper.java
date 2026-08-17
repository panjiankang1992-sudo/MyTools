package com.yuyutian.mytools.reader.mapper;

import com.yuyutian.mytools.reader.model.ShelfBook;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

import java.util.List;

/**
 * 书架元数据访问接口。
 */
@Mapper
public interface ShelfBookMapper {
    /** 删除用户全部书架数据。 @param userId 用户ID @return 删除行数 */
    @Delete("DELETE FROM t_shelf_book WHERE user_id = #{userId}")
    int deleteByUserId(Long userId);

    /**
     * 查询用户书架及删除墓碑。
     *
     * @param userId 用户ID
     * @return 书架记录
     */
    @Select("SELECT user_id AS userId, sync_key AS syncKey, book_id AS bookId, name, author, origin, format, "
            + "resource_uri AS resourceUri, source_id AS sourceId, remote_cover_url AS remoteCoverUrl, "
            + "client_updated_at AS clientUpdatedAt, server_updated_at AS serverUpdatedAt, deleted, revision "
            + "FROM t_shelf_book WHERE user_id = #{userId} ORDER BY server_updated_at LIMIT 5000")
    List<ShelfBook> findAllByUserId(Long userId);

    /**
     * 查询单本同步书籍。
     *
     * @param userId 用户ID
     * @param syncKey 图书摘要键
     * @return 书架记录
     */
    @Select("SELECT user_id AS userId, sync_key AS syncKey, book_id AS bookId, name, author, origin, format, "
            + "resource_uri AS resourceUri, source_id AS sourceId, remote_cover_url AS remoteCoverUrl, "
            + "client_updated_at AS clientUpdatedAt, server_updated_at AS serverUpdatedAt, deleted, revision "
            + "FROM t_shelf_book WHERE user_id = #{userId} AND sync_key = #{syncKey}")
    ShelfBook findById(@Param("userId") Long userId, @Param("syncKey") String syncKey);

    /**
     * 统计用户书架记录和墓碑。
     *
     * @param userId 用户ID
     * @return 记录数
     */
    @Select("SELECT COUNT(*) FROM t_shelf_book WHERE user_id = #{userId}")
    long countByUserId(Long userId);

    /**
     * 新增书架记录。
     *
     * @param book 书架记录
     * @return 影响行数
     */
    @Insert("INSERT INTO t_shelf_book (user_id, sync_key, book_id, name, author, origin, format, resource_uri, "
            + "source_id, remote_cover_url, client_updated_at, server_updated_at, deleted, revision) VALUES "
            + "(#{userId}, #{syncKey}, #{bookId}, #{name}, #{author}, #{origin}, #{format}, #{resourceUri}, "
            + "#{sourceId}, #{remoteCoverUrl}, #{clientUpdatedAt}, #{serverUpdatedAt}, #{deleted}, 1)")
    int insert(ShelfBook book);

    /**
     * 使用乐观锁更新书架记录。
     *
     * @param book 书架记录
     * @param expectedRevision 基准版本
     * @return 影响行数
     */
    @Update("UPDATE t_shelf_book SET book_id = #{book.bookId}, name = #{book.name}, author = #{book.author}, "
            + "origin = #{book.origin}, format = #{book.format}, resource_uri = #{book.resourceUri}, "
            + "source_id = #{book.sourceId}, remote_cover_url = #{book.remoteCoverUrl}, "
            + "client_updated_at = #{book.clientUpdatedAt}, server_updated_at = #{book.serverUpdatedAt}, "
            + "deleted = #{book.deleted}, revision = revision + 1 WHERE user_id = #{book.userId} "
            + "AND sync_key = #{book.syncKey} AND revision = #{expectedRevision}")
    int updateIfRevisionMatches(@Param("book") ShelfBook book, @Param("expectedRevision") Long expectedRevision);
}
