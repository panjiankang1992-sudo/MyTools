package com.yuyutian.mytools.feedback.mapper;

import com.yuyutian.mytools.feedback.model.Feedback;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 问题反馈数据访问接口。
 */
@Mapper
public interface FeedbackMapper {

    /**
     * 新增问题反馈。
     *
     * @param feedback 问题反馈实体
     * @return 受影响行数
     */
    @Insert("INSERT INTO t_feedback (id, username, email, phone, category, title, content, status, created_time, update_time) "
            + "VALUES (#{id}, #{username}, #{email}, #{phone}, #{category}, #{title}, #{content}, #{status}, #{createdTime}, #{updateTime})")
    int insert(Feedback feedback);
}
