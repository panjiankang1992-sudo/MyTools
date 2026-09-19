package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.lang.reflect.InvocationTargetException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.yuyutian.mytools.reader.repository.adaptation.AdaptationContextRepository;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationContextSnapshot;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationExecutionViews;

/** 仅在服务器内存中检查一个冻结上下文，不导出正文、不调用模型或写数据库。 */
class DiagnoseAdaptationContext {
    /** 诊断输出只有阶段和代码位置，连接信息仅通过私有子进程环境提供。 */
    public static void main(String[] arguments) throws Exception {
        String stage = "database";
        try (var db = DriverManager.getConnection(System.getenv("ADAPT_DIAG_JDBC"),
                System.getenv("ADAPT_DIAG_USER"), System.getenv("ADAPT_DIAG_PASSWORD"))) {
            db.setReadOnly(true);
            var mapper = new ObjectMapper();
            stage = "reader-stored-context";
            var source = new SingleConnectionDataSource(db, true);
            var jdbc = new JdbcTemplate(source);
            var repository = new AdaptationContextRepository(jdbc, mapper, new DataSourceTransactionManager(source));
            var stored = AdaptationContextRepository.class.getDeclaredMethod("stored", java.util.Map.class);
            stored.setAccessible(true);
            var rowData = jdbc.queryForMap("SELECT * FROM novel_chapter_adaptation WHERE id=?", "db04d1c2-ea51-42ef-92fc-19892c256319");
            var snapshot = (AdaptationContextSnapshot) stored.invoke(repository, rowData);
            System.out.println("CONTEXT_AND_READER_STORED_VALID");
            var inputs = mapper.createObjectNode();
            var context = mapper.createObjectNode();
            try (var query = db.prepareStatement("SELECT generation_intent_text,prompt_version,constraint_version,context_manifest_sha256,request_kind FROM novel_chapter_adaptation WHERE id=?")) {
                query.setString(1, "db04d1c2-ea51-42ef-92fc-19892c256319");
                try (var row = query.executeQuery()) {
                    if (!row.next()) throw new IllegalStateException();
                    inputs.put("intent", row.getString(1)).put("promptVersion", row.getString(2)).put("constraintVersion", row.getString(3));
                    context.put("manifestVersion", "adaptation-context-framed-v1").put("manifestSha256", row.getString(4)).put("kind", row.getString(5));
                }
            }
            var fragments = context.putArray("fragments");
            try (var query = db.prepareStatement("SELECT context_role,source_chapter_id,content_text FROM novel_chapter_adaptation_context WHERE adaptation_id=?")) {
                query.setString(1, "db04d1c2-ea51-42ef-92fc-19892c256319");
                try (var row = query.executeQuery()) {
                    while (row.next()) fragments.addObject().put("role", row.getString(1)).put("sourceChapterId", row.getString(2)).put("text", row.getString(3));
                }
            }
            stage = "workflow-context";
            var method = NovelAdaptationWorkflow.class.getDeclaredMethod("context", JsonNode.class, JsonNode.class);
            method.setAccessible(true);
            var parsed = (NovelAdaptationPrompts.Context) method.invoke(null, inputs, context);
            stage = "reader-wire-context";
            method.invoke(null, inputs, mapper.valueToTree(AdaptationExecutionViews.context(snapshot)));
            stage = "plan-protocol";
            var plan = new NovelAdaptationPrompts().plan(parsed).rewriteProtocol();
            System.out.println("CONTEXT_AND_PLAN_VALID fullRewrite=" + plan.fullRewrite());
        } catch (Throwable error) {
            if (error instanceof InvocationTargetException invocation) error = invocation.getCause();
            System.out.println("DIAGNOSTIC_FAILURE stage=" + stage + " type=" + error.getClass().getSimpleName());
            for (var frame : error.getStackTrace()) {
                if (frame.getClassName().startsWith("com.yuyutian")) System.out.println(frame.getClassName() + ":" + frame.getMethodName() + ":" + frame.getLineNumber());
            }
            System.exit(1);
        }
    }
}
