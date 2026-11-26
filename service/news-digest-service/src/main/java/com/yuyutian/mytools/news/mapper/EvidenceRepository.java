package com.yuyutian.mytools.news.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.NewsModels.Article;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 批量读取日报中真实引用的版本，避免逐条查询占用数据库。 */
@Repository
public class EvidenceRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    /** 复用受控数据库连接与版本解码器。 */
    public EvidenceRepository(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=new NamedParameterJdbcTemplate(jdbc);this.json=json; }
    /** 输入只来自通过所有者校验后的日报，空引用无需访问数据库。 */
    public Map<String,Stored> find(Collection<String> ids) {
        if(ids.isEmpty())return Map.of();
        return jdbc.query("SELECT a.payload_json,a.evidence_expired_ms,r.evidence_hours FROM news_article a LEFT JOIN news_source_retention r ON r.source_id=a.source_id WHERE a.id IN (:ids)",
                Map.of("ids",ids),(row,index)->new Stored(decode(row.getString(1)),row.getObject(2,Long.class)!=null,row.getObject(3,Integer.class)))
                .stream().collect(Collectors.toMap(row->row.article().id(),Function.identity()));
    }
    private Article decode(String value) {
        try{return json.readValue(value,Article.class);}
        catch(JsonProcessingException exception){throw new NewsException(ErrorCode.NEWS_009);}
    }
    /** 内部持久状态不得直接通过接口序列化。 */
    public record Stored(Article article,boolean expired,Integer hours) { }
}
