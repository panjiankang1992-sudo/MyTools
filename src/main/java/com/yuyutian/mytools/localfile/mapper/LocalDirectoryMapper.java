package com.yuyutian.mytools.localfile.mapper;

import com.yuyutian.mytools.localfile.entity.LocalDirectory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 本地文件目录 Mapper。
 *
 * @author mytools
 * @since 2026-05-10
 */
@Mapper
public interface LocalDirectoryMapper {

    /**
     * 查询所有目录。
     */
    @Select("SELECT * FROM local_directory ORDER BY create_time DESC")
    List<LocalDirectory> selectAll();

    /**
     * 根据类型查询目录。
     */
    @Select("SELECT * FROM local_directory WHERE directory_type = #{directoryType}")
    LocalDirectory selectByType(String directoryType);

    /**
     * 根据 ID 查询目录。
     */
    @Select("SELECT * FROM local_directory WHERE id = #{id}")
    LocalDirectory selectById(Long id);

    /**
     * 更新最后扫描时间。
     */
    @Update("UPDATE local_directory SET last_scan_time = #{lastScanTime}, update_time = #{updateTime} WHERE id = #{id}")
    void updateLastScanTime(Long id, java.time.LocalDateTime lastScanTime, java.time.LocalDateTime updateTime);
}
