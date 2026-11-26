package com.yuyutian.mytools.toolbox.service.admin.curriculum;

/** 已发布教材到草稿的共享无损转换器，供导入及三方同步使用。 */
public final class PublishedBookDraftConverter {
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** 创建使用项目 JSON 配置的转换器。 */
    public PublishedBookDraftConverter(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private static final java.util.List<String> PRESERVED_ENTRY_FIELDS = java.util.List.of(
            "pinyinSegments", "partOfSpeech", "senseId", "context", "promptContext",
            "acceptableAnswers", "sourceRefs", "form", "entryVersion",
            "reviewStatus", "blockingIssues", "listTypes");

    /**
     * 为缺少来源说明的迁移包派生一条**事实性**说明。
     *
     * **不编造来源**：只用包里已有的 `edition.sourceIds` 与条目级的 `sourceRefs` 计数，
     * 并显式标注「迁移自动生成」，便于管理员替换为真实描述。
     * 若连 sourceIds 也没有，则给出"来源未记录"，让发布前的校验如实拦下。
     *
     * @param edition 正文的 edition 块
     * @param book 正文根节点
     * @return 来源说明
     */
    private static String migrationSourceNote(com.fasterxml.jackson.databind.JsonNode edition,
                                              com.fasterxml.jackson.databind.JsonNode book) {
        var ids = new java.util.ArrayList<String>();
        if (edition.path("sourceIds").isArray()) {
            for (var id : edition.path("sourceIds")) {
                if (id.isTextual() && !id.asText().isBlank()) {
                    ids.add(id.asText());
                }
            }
        }
        int referenced = 0;
        int pageFrom = Integer.MAX_VALUE;
        int pageTo = Integer.MIN_VALUE;
        for (var entry : book.path("entries")) {
            for (var ref : entry.path("sourceRefs")) {
                referenced += 1;
                if (ref.path("page").isNumber()) {
                    int page = ref.path("page").asInt();
                    pageFrom = Math.min(pageFrom, page);
                    pageTo = Math.max(pageTo, page);
                }
            }
        }
        String pageText = pageFrom == Integer.MAX_VALUE
                ? "页码未记录"
                : (pageFrom == pageTo ? "第 " + pageFrom + " 页" : "第 " + pageFrom + "—" + pageTo + " 页");
        if (ids.isEmpty()) {
            return "（迁移自动生成）来源未记录；条目引用 " + referenced + " 处，" + pageText + "。请补充真实来源说明。";
        }
        return "（迁移自动生成）来源 " + String.join("、", ids) + "；条目引用 " + referenced + " 处，"
                + pageText + "。请按纸质教材核实后替换。";
    }

    /** 将不可变教材转换为可编辑草稿；转换失败时拒绝合并，避免空内容覆盖。 */
    public String convert(String editionId, byte[] bookBytes) {
        try {
            var book = objectMapper.readTree(bookBytes);
            var edition = book.path("edition");
            var draft = objectMapper.createObjectNode();
            draft.put("editionId", editionId);
            draft.put("subject", edition.path("subject").asText("chinese"));
            draft.put("grade", edition.path("grade").asInt(1));
            draft.put("semester", edition.path("semester").asText("first"));
            draft.put("publisher", edition.path("publisher").asText(""));
            draft.put("series", edition.path("series").asText(""));
            draft.put("editionLabel", edition.path("editionLabel").asText(""));
            // **sourceNote 是发布必填**（设计第 106 行）。迁移包里的 `edition.sourceNote`
            // 往往是空的，于是迁移进来的草稿一发布就 422 `SOURCE_REQUIRED`。
            //
            // 处理原则：**只从已冻结的真实数据派生一条事实性说明，不编造来源**。
            // 说明里带上来源 ID 与引用条数，并**显式标注"迁移自动生成"**，
            // 便于管理员按真实情况替换（而不是让它看起来像人工填写的）。
            String declaredNote = edition.path("sourceNote").asText("");
            draft.put("sourceNote", declaredNote.isBlank()
                    ? migrationSourceNote(edition, book)
                    : declaredNote);
            draft.put("coverageDeclaration", edition.path("coverageStatus").asText("partial"));
            // 教材级来源标识**具名带过来**：发布时按它冻结完整来源对象；
            // 继续让它只存在于正文的 edition 块里会让"改一次草稿就丢来源"。
            if (edition.path("sourceIds").isArray()) {
                draft.set("sourceIds", edition.path("sourceIds"));
            }
            // **适用地区／课标年份／ISBN 必须一起搬过来**。
            //
            // 此前这里不写这三个字段，于是 `edition_metadata_json` 里的
            // `regionCodes=["jiangsu"]`、`isbn` 在迁移草稿里**恒为 null**；
            // `AdminTextbookService.toEdition` 虽然已经改成传真实值，
            // **却拿不到值**，发布出去的新版本就把它们丢成 null。
            //
            // 实测后果（2026-10-07，真实 5 册迁移）：`GET /public/v1/textbooks?region=jiangsu`
            // 只剩 2 册 —— **迁移后重新发布的教材在地区筛选下消失**。
            //
            // 注意用**真值判断**而不是 `asInt(0)` / `asText("")`：
            // 年份 0 与空串 ISBN 都是"看起来有值但其实没有"，会污染后续对账。
            if (edition.path("regionCodes").isArray()) {
                draft.set("regionCodes", edition.path("regionCodes"));
            }
            if (edition.path("curriculumYear").isNumber()) {
                draft.put("curriculumYear", edition.path("curriculumYear").asInt());
            }
            if (edition.path("isbn").isTextual() && !edition.path("isbn").asText().isBlank()) {
                draft.put("isbn", edition.path("isbn").asText());
            }

            var idMapping = objectMapper.createObjectNode();
            var units = objectMapper.createArrayNode();
            for (var unit : book.path("units")) {
                String officialId = unit.path("unitId").asText();
                var node = objectMapper.createObjectNode();
                node.put("unitId", officialId);
                // 用正式 ID 作为客户端标识：打开即可见、且改标题/调顺序时不换身份。
                node.put("clientUnitId", officialId);
                node.put("title", unit.path("title").asText(""));
                node.put("order", unit.path("order").asInt(0));
                units.add(node);
                idMapping.put("u:" + officialId, officialId);
            }
            draft.set("units", units);

            var lessons = objectMapper.createArrayNode();
            for (var lesson : book.path("lessons")) {
                String officialId = lesson.path("lessonId").asText();
                var node = objectMapper.createObjectNode();
                node.put("lessonId", officialId);
                node.put("clientLessonId", officialId);
                node.put("unitId", lesson.path("unitId").asText(""));
                node.put("title", lesson.path("title").asText(""));
                node.put("order", lesson.path("order").asInt(0));
                lessons.add(node);
                idMapping.put("l:" + officialId, officialId);
            }
            draft.set("lessons", lessons);

            var entries = objectMapper.createArrayNode();
            for (var entry : book.path("entries")) {
                String officialId = entry.path("entryId").asText();
                var node = objectMapper.createObjectNode();
                node.put("rowId", officialId);
                node.put("entryId", officialId);
                node.put("unitId", entry.path("unitId").asText(""));
                node.put("clientUnitId", entry.path("unitId").asText(""));
                if (entry.hasNonNull("lessonId")) {
                    node.put("lessonId", entry.path("lessonId").asText());
                    node.put("clientLessonId", entry.path("lessonId").asText());
                }
                node.put("text", entry.path("text").asText(""));
                node.put("pinyin", entry.path("pinyinDisplay").asText(""));
                node.put("meaning", entry.path("meaning").asText(""));
                node.set("listTypes", entry.path("listTypes"));
                // 已发布内容视为已核对：发布本身就是人工确认的结果。
                node.put("confirmed", true);
                // **已初始化**：这些行来自正式教材，不能被自动补全再改写一次。
                node.put("automaticInitialized", true);
                // 暂不由编辑器暴露、但**必须原样保留**的字段。
                // 不保留它们，管理员改一个字就会把整册书的义项、上下文、来源和
                // 拼音分段全部清空——而且不会报错。
                //
                // 不含 `order`：顺序由草稿数组的位置表达，映射时按下标重新编号，
                // 再单独保存一份反而会出现"两个顺序"互相矛盾。
                var preserved = objectMapper.createObjectNode();
                for (String field : PRESERVED_ENTRY_FIELDS) {
                    if (entry.hasNonNull(field)) {
                        preserved.set(field, entry.path(field));
                    }
                }
                if (preserved.size() > 0) {
                    node.set("preserved", preserved);
                }
                // 条目级来源定位同时写成**具名字段**：编辑器可以直接增删改，
                // 不再只能靠不透明映射往返。`preserved.sourceRefs` 仍然保留，兼容旧读取方。
                if (entry.path("sourceRefs").isArray()) {
                    node.set("sourceRefs", entry.path("sourceRefs"));
                }
                entries.add(node);
                idMapping.put("e:" + officialId, officialId);
            }
            draft.set("entries", entries);
            draft.set("idMapping", idMapping);
            return objectMapper.writeValueAsString(draft);
        } catch (Exception failure) {
            throw new IllegalArgumentException("publishedBookUnparsable", failure);
        }
    }

}
