package com.yuyutian.mytools.reader.mapper;

import com.yuyutian.mytools.reader.model.SyncedBookSource;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

import java.util.List;

/**
 * 同步书源数据访问接口。
 */
@Mapper
public interface SyncedBookSourceMapper {
    /** 删除用户全部书源快照。 @param userId 用户ID @return 删除行数 */
    @Delete("DELETE FROM t_synced_book_source WHERE user_id = #{userId}")
    int deleteByUserId(Long userId);

    /** 查询用户书源及墓碑。 @param userId 用户ID @return 书源列表 */
    @Select("SELECT user_id AS userId, sync_key AS syncKey, source_url AS sourceUrl, snapshot_json AS snapshotJson, "
            + "client_updated_at AS clientUpdatedAt, server_updated_at AS serverUpdatedAt, deleted, revision "
            + "FROM t_synced_book_source WHERE user_id = #{userId} ORDER BY server_updated_at LIMIT 500")
    List<SyncedBookSource> findAllByUserId(Long userId);

    /** 查询单个书源。 @param userId 用户ID @param syncKey 摘要键 @return 书源记录 */
    @Select("SELECT user_id AS userId, sync_key AS syncKey, source_url AS sourceUrl, snapshot_json AS snapshotJson, "
            + "client_updated_at AS clientUpdatedAt, server_updated_at AS serverUpdatedAt, deleted, revision "
            + "FROM t_synced_book_source WHERE user_id = #{userId} AND sync_key = #{syncKey}")
    SyncedBookSource findById(@Param("userId") Long userId, @Param("syncKey") String syncKey);

    /** 统计用户书源。 @param userId 用户ID @return 记录数 */
    @Select("SELECT COUNT(*) FROM t_synced_book_source WHERE user_id = #{userId}")
    long countByUserId(Long userId);

    /** 新增书源。 @param source 书源记录 @return 影响行数 */
    @Insert("INSERT INTO t_synced_book_source (user_id, sync_key, source_url, snapshot_json, client_updated_at, "
            + "server_updated_at, deleted, revision) VALUES (#{userId}, #{syncKey}, #{sourceUrl}, "
            + "#{snapshotJson}, #{clientUpdatedAt}, #{serverUpdatedAt}, #{deleted}, 1)")
    int insert(SyncedBookSource source);

    /** 乐观锁更新书源。 @param source 书源记录 @param expectedRevision 基准版本 @return 影响行数 */
    @Update("UPDATE t_synced_book_source SET source_url = #{source.sourceUrl}, snapshot_json = #{source.snapshotJson}, "
            + "client_updated_at = #{source.clientUpdatedAt}, server_updated_at = #{source.serverUpdatedAt}, "
            + "deleted = #{source.deleted}, revision = revision + 1 WHERE user_id = #{source.userId} "
            + "AND sync_key = #{source.syncKey} AND revision = #{expectedRevision}")
    int updateIfRevisionMatches(@Param("source") SyncedBookSource source,
                                @Param("expectedRevision") Long expectedRevision);
}
