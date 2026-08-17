package com.yuyutian.mytools.drive.mapper;

import com.yuyutian.mytools.drive.model.DriveAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 统一网盘账号数据访问层。
 */
@Mapper
public interface DriveAccountMapper {

    /** 查询用户全部启用网盘。 */
    @Select("SELECT * FROM drive_account WHERE user_id = #{userId} AND enabled = 1 ORDER BY id ASC")
    List<DriveAccount> selectEnabledByUserId(@Param("userId") Long userId);

    /** 查询用户有权访问的网盘。 */
    @Select("SELECT * FROM drive_account WHERE id = #{driveId} AND user_id = #{userId} AND enabled = 1")
    DriveAccount selectOwned(@Param("driveId") Long driveId, @Param("userId") Long userId);
}
