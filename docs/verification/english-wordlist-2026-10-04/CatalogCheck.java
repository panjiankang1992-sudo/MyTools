import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.toolbox.service.curriculum.CurriculumCatalog;
import com.yuyutian.mytools.toolbox.service.curriculum.EntryQuery;
import com.yuyutian.mytools.toolbox.model.ListType;
import java.nio.file.Path;
import java.util.List;

/** 使用真实服务目录加载器校验本轮内容包，不启动服务或连接数据库。 */
class CatalogCheck {
    /** 读取目录并确认真实单元及附录数量。 */
    public static void main(String[] arguments) {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var catalog = new CurriculumCatalog(Path.of(arguments[0]), mapper);
        catalog.load();
        var editionId = "en-yl-3-1-yilin-2024";
        var detail = catalog.version(editionId, "1.0.1").orElseThrow();
        if (catalog.editionCount() != 5 || detail.units().size() != 9 || !detail.lessons().isEmpty()) {
            throw new IllegalStateException("Catalog structure mismatch");
        }
        int[] counts = {13, 11, 17, 9, 18, 12, 31, 18, 11};
        for (int index = 0; index < counts.length; index++) {
            // 按实际服务过滤规则逐单元验证，防止附录专名再次混入正文单元。
            var result = catalog.entries(new EntryQuery(editionId, "1.0.1",
                    List.of(detail.units().get(index).unitId()), List.of(), List.of(ListType.VOCABULARY), null, 500));
            if (result.entries().size() != counts[index] || result.total() != counts[index]) {
                throw new IllegalStateException("Unit entry count mismatch");
            }
        }
        System.out.println("Catalog loader passed: 5 editions; English 1.0.1; 8 units with 129 entries; appendix with 11 entries.");
    }
}
