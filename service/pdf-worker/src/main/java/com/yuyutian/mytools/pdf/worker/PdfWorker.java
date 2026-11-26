package com.yuyutian.mytools.pdf.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.cos.COSName;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** 离线处理受管文件，所有路径均由可信包装器在任务目录中构造。 */
public final class PdfWorker {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_PAGES = 1000;
    private PdfWorker() { }

    /** 从任务请求读取参数并输出脱敏的清单或稳定错误码。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("PDF_001");
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        try {
            JsonNode request = JSON.readTree(Path.of(args[0]).toFile());
            JSON.writeValue(output.resolve("manifest.json").toFile(), process(request, output));
        } catch (Exception failure) {
            String code = failure.getMessage();
            if (failure instanceof org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException) code = "PDF_004";
            if (code == null || !code.matches("PDF_[0-9]{3}")) code = "PDF_010";
            Files.writeString(output.resolve("error.json"), "{\"code\":\"" + code + "\"}");
            System.exit(1);
        }
    }

    /** 执行固定操作，限制页数、像素及文件数量。 */
    public static ObjectNode process(JsonNode request, Path output) throws Exception {
        Files.createDirectories(output);
        String operation = request.path("operation").asText();
        List<Path> inputs = new ArrayList<>();
        for (JsonNode value : request.path("inputPaths")) inputs.add(Path.of(value.asText()));
        JsonNode options = request.path("options");
        ObjectNode result = JSON.createObjectNode();
        result.put("engineVersion", "pdfbox-3.0.8/1.0.0");
        var artifacts = result.putArray("artifacts");
        var warnings = result.putArray("warnings");
        if (operation.equals("INSPECT")) return inspect(inputs.getFirst(), request.path("mediaType").asText(), output);
        if (operation.equals("IMAGES_TO_PDF")) {
            if (inputs.isEmpty() || inputs.size() > 100) fail("PDF_003");
            try (PDDocument target = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
                for (Path input : inputs) {
                    BufferedImage image = readImage(input);
                    PDRectangle paper = options.path("paperSize").asText("A4").equals("A5") ? PDRectangle.A5 : PDRectangle.A4;
                    String orientation = options.path("orientation").asText("AUTO");
                    if (orientation.equals("LANDSCAPE") || orientation.equals("AUTO") && image.getWidth() > image.getHeight())
                        paper = new PDRectangle(paper.getHeight(), paper.getWidth());
                    PDPage page = new PDPage(paper); target.addPage(page);
                    float margin = (float) options.path("marginMm").asDouble(10) * 72 / 25.4f;
                    float availableWidth = paper.getWidth() - 2 * margin, availableHeight = paper.getHeight() - 2 * margin;
                    if (availableWidth < 10 || availableHeight < 10) fail("PDF_001");
                    float sx = availableWidth / image.getWidth(), sy = availableHeight / image.getHeight();
                    float scale = options.path("fit").asText("FIT").equals("FILL") ? Math.max(sx, sy) : Math.min(sx, sy);
                    try (PDPageContentStream stream = new PDPageContentStream(target, page)) {
                        stream.addRect(margin, margin, availableWidth, availableHeight); stream.clip();
                        stream.drawImage(LosslessFactory.createFromImage(target, image),
                                (paper.getWidth() - image.getWidth() * scale) / 2,
                                (paper.getHeight() - image.getHeight() * scale) / 2,
                                image.getWidth() * scale, image.getHeight() * scale);
                    }
                }
                save(target, output.resolve("result.pdf"));
            }
        } else if (operation.equals("PDF_MERGE")) {
            if (inputs.size() < 2 || inputs.size() > 100) fail("PDF_001");
            try (PDDocument target = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
                for (Path input : inputs) try (PDDocument source = open(input)) {
                    check(source);
                    for (PDPage page : source.getPages()) copy(target, page);
                    if (target.getNumberOfPages() > MAX_PAGES) fail("PDF_003");
                }
                save(target, output.resolve("result.pdf"));
            }
        } else {
            if (inputs.size() != 1) fail("PDF_001");
            try (PDDocument source = open(inputs.getFirst())) {
                check(source);
                List<Integer> selected = selection(options.path("ranges"), source.getNumberOfPages());
                if (operation.equals("PDF_PAGE_CUT")) {
                    double position = options.path("position").asDouble(0.5);
                    if (!Double.isFinite(position) || position < .05 || position > .95) fail("PDF_001");
                    boolean vertical = options.path("axis").asText("VERTICAL").equals("VERTICAL");
                    boolean reverse = options.path("reverse").asBoolean();
                    try (PDDocument target = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
                        for (int index = 0; index < source.getNumberOfPages(); index++) {
                            PDPage page = source.getPage(index);
                            if (!selected.contains(index)) { copy(target, page); continue; }
                            for (int part = 0; part < 2; part++) {
                                int side = reverse ? 1 - part : part;
                                double start = side == 0 ? 0 : position, end = side == 0 ? position : 1;
                                PDRectangle box = region(page, vertical ? start : 0, vertical ? 0 : start,
                                        vertical ? end : 1, vertical ? 1 : end);
                                if (box.getWidth() * page.getUserUnit() < 10 || box.getHeight() * page.getUserUnit() < 10) fail("PDF_001");
                                PDPage cloned = copy(target, page); cloned.setMediaBox(box); cloned.setCropBox(box);
                            }
                        }
                        save(target, output.resolve("result.pdf"));
                    }
                    warnings.add("CROP_IS_NOT_REDACTION");
                } else if (operation.equals("PDF_SPLIT")) {
                    List<List<Integer>> groups = new ArrayList<>();
                    if (options.has("groups")) {
                        for (JsonNode group : options.path("groups")) groups.add(selection(group, source.getNumberOfPages()));
                    } else {
                        int count = options.path("pagesPerFile").asInt(1);
                        if (count < 1 || count > MAX_PAGES) fail("PDF_001");
                        for (int i = 0; i < selected.size(); i += count) groups.add(selected.subList(i, Math.min(i + count, selected.size())));
                    }
                    if (groups.isEmpty() || groups.size() > 100) fail("PDF_003");
                    int index = 1, total = 0;
                    for (List<Integer> group : groups) {
                        total += group.size(); if (total > MAX_PAGES) fail("PDF_003");
                        try (PDDocument target = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
                            for (int page : group) copy(target, source.getPage(page));
                            save(target, output.resolve(String.format(Locale.ROOT, "part-%03d.pdf", index++)));
                        }
                    }
                } else if (operation.equals("PDF_TO_IMAGES")) {
                    int dpi = options.path("dpi").asInt(150);
                    if (dpi < 72 || dpi > 300 || selected.size() > 100) fail("PDF_003");
                    String format = options.path("format").asText("PNG").equals("JPG") ? "jpg" : "png";
                    PDFRenderer renderer = new PDFRenderer(source);
                    for (int index : selected) {
                        pixels(source.getPage(index), dpi);
                        BufferedImage image = renderer.renderImageWithDPI(index, dpi);
                        ImageIO.write(image, format, output.resolve(String.format(Locale.ROOT, "page-%04d.%s", index + 1, format)).toFile());
                    }
                } else if (operation.equals("PDF_TO_TEXT")) {
                    if (!source.getCurrentAccessPermission().canExtractContent()) fail("PDF_005");
                    PDFTextStripper stripper = new PDFTextStripper(); stripper.setSortByPosition(true);
                    StringBuilder text = new StringBuilder();
                    for (int index : selected) {
                        stripper.setStartPage(index + 1); stripper.setEndPage(index + 1);
                        String content = stripper.getText(source);
                        if (content.isBlank()) fail("PDF_013");
                        if (!text.isEmpty()) text.append('\f');
                        text.append(content);
                        if (text.length() > 10_000_000) fail("PDF_003");
                    }
                    Files.writeString(output.resolve("result.txt"), text, StandardCharsets.UTF_8);
                    result.put("textPreview", text.substring(0, Math.min(text.length(), 2000)));
                    result.put("pageCount", selected.size());
                } else fail("PDF_001");
                if (source.getDocumentCatalog().getDocumentOutline() != null) warnings.add("BOOKMARKS_NOT_PRESERVED");
                if (source.getDocumentCatalog().getMarkInfo() != null || source.getDocumentCatalog().getMetadata() != null)
                    warnings.add("DOCUMENT_CONFORMANCE_NOT_PRESERVED");
            }
        }
        int pages = 0;
        List<Path> files;
        try (var list = Files.list(output)) { files = list.filter(Files::isRegularFile).sorted().toList(); }
        for (Path file : files) {
            ObjectNode artifact = artifacts.addObject(); artifact.put("filename", file.getFileName().toString());
            artifact.put("bytes", Files.size(file)); artifact.put("mediaType", mime(file));
            if (file.toString().endsWith(".pdf")) try (PDDocument document = open(file)) {
                pages += document.getNumberOfPages(); artifact.put("pageCount", document.getNumberOfPages());
                if (pages > MAX_PAGES) fail("PDF_003");
                // 逐个成品首尾渲染，防止发布不可见或无法读取的结果。
                PDFRenderer renderer = new PDFRenderer(document);
                pixels(document.getPage(0), 72); renderer.renderImage(0, .5f);
                pixels(document.getPage(document.getNumberOfPages() - 1), 72); renderer.renderImage(document.getNumberOfPages() - 1, .5f);
            }
        }
        if (files.size() > 1) {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output.resolve("results.zip")))) {
                for (Path file : files) { zip.putNextEntry(new ZipEntry(file.getFileName().toString())); Files.copy(file, zip); zip.closeEntry(); }
            }
            artifacts.addObject().put("filename", "results.zip").put("mediaType", "application/zip").put("bytes", Files.size(output.resolve("results.zip")));
        }
        result.put("pageCount", pages > 0 ? pages : operation.equals("PDF_TO_IMAGES") ? files.size() : result.path("pageCount").asInt()); result.put("fileCount", files.size());
        // 页面预览按 PDF 页面建立有界缩略图，不将诊断文件放进结果。
        int preview = 0;
        var previews = result.putArray("previews");
        for (Path file : files) {
            if (file.toString().endsWith(".png") || file.toString().endsWith(".jpg")) {
                if (preview < 40) {
                    BufferedImage image = ImageIO.read(file.toFile());
                    float scale = Math.min(1, 800f / Math.max(image.getWidth(), image.getHeight()));
                    BufferedImage small = new BufferedImage(Math.max(1,(int)(image.getWidth()*scale)),Math.max(1,(int)(image.getHeight()*scale)),BufferedImage.TYPE_INT_RGB);
                    var graphics = small.createGraphics();graphics.drawImage(image,0,0,small.getWidth(),small.getHeight(),null);graphics.dispose();
                    String name="preview-"+(++preview)+".png";ImageIO.write(small,"png",output.resolve(name).toFile());
                    previews.addObject().put("filename",name).put("mediaType","image/png").put("index",preview);
                }
                continue;
            }
            if (!file.toString().endsWith(".pdf")) continue;
            try (PDDocument doc = open(file)) {
                PDFRenderer renderer = new PDFRenderer(doc);
                for (int index = 0; index < doc.getNumberOfPages() && preview < 40; index++) {
                    PDRectangle box = doc.getPage(index).getCropBox();
                    pixels(doc.getPage(index), 72);
                    String name = "preview-" + (++preview) + ".png";
                    float scale = Math.min(1, 800 / Math.max(box.getWidth(), box.getHeight()));
                    ImageIO.write(renderer.renderImage(index, scale), "png", output.resolve(name).toFile());
                    previews.addObject().put("filename", name).put("mediaType", "image/png").put("index", preview);
                }
            }
        }
        return result;
    }

    /** 根据有效裁切框和旋转将显示坐标反投影到源页面坐标。 */
    public static PDRectangle region(PDPage page, double left, double top, double right, double bottom) {
        PDRectangle crop = page.getCropBox(), media = page.getMediaBox();
        float x0 = Math.max(crop.getLowerLeftX(), media.getLowerLeftX()), y0 = Math.max(crop.getLowerLeftY(), media.getLowerLeftY());
        float width = Math.min(crop.getUpperRightX(), media.getUpperRightX()) - x0;
        float height = Math.min(crop.getUpperRightY(), media.getUpperRightY()) - y0;
        double minX = Double.POSITIVE_INFINITY, minY = minX, maxX = Double.NEGATIVE_INFINITY, maxY = maxX;
        for (double u : new double[]{left, right}) for (double v : new double[]{top, bottom}) {
            double x, y;
            switch (Math.floorMod(page.getRotation(), 360)) {
                case 90 -> { x = v; y = u; }
                case 180 -> { x = 1 - u; y = v; }
                case 270 -> { x = 1 - v; y = 1 - u; }
                default -> { x = u; y = 1 - v; }
            }
            minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        return new PDRectangle(x0 + (float) minX * width, y0 + (float) minY * height,
                (float) (maxX - minX) * width, (float) (maxY - minY) * height);
    }

    private static ObjectNode inspect(Path input, String type, Path output) throws Exception {
        ObjectNode result = JSON.createObjectNode(); result.put("mediaType", type); result.put("bytes", Files.size(input));
        if (type.equals("application/pdf")) try (PDDocument doc = open(input)) {
            check(doc); result.put("pageCount", doc.getNumberOfPages());
            var pages = result.putArray("pages");
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDPage page = doc.getPage(i); PDRectangle box = page.getCropBox();
                pages.addObject().put("number", i + 1).put("width", box.getWidth()).put("height", box.getHeight()).put("rotation", page.getRotation());
            }
            pixels(doc.getPage(0), 72);
            float scale = Math.min(1, 800 / Math.max(doc.getPage(0).getCropBox().getWidth(), doc.getPage(0).getCropBox().getHeight()));
            ImageIO.write(renderer.renderImage(0, scale), "png", output.resolve("preview-1.png").toFile());
            result.putArray("previews").addObject().put("filename", "preview-1.png").put("mediaType", "image/png").put("index", 1);
        } else if (type.startsWith("image/")) {
            BufferedImage image = readImage(input); result.put("width", image.getWidth()).put("height", image.getHeight());
        } else if (type.equals("text/plain") || type.equals("text/markdown")) {
            if (Files.size(input) > 262144) fail("PDF_003");
            String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(input))).toString();
            if (text.indexOf('\0') >= 0) fail("PDF_002");
        } else fail("PDF_002");
        return result;
    }

    private static PDDocument open(Path input) throws IOException { return Loader.loadPDF(input.toFile(), IOUtils.createTempFileOnlyStreamCache()); }
    private static void check(PDDocument doc) throws IOException {
        if (doc.getNumberOfPages() < 1 || doc.getNumberOfPages() > 500) fail("PDF_003");
        if (!doc.getCurrentAccessPermission().canModify()) fail("PDF_005");
        if (!doc.getSignatureDictionaries().isEmpty() || doc.getDocumentCatalog().getAcroForm() != null) fail("PDF_006");
        for (PDPage page : doc.getPages()) {
            if (!page.getAnnotations().isEmpty()) fail("PDF_006");
            if (page.getRotation() % 90 != 0 || !Float.isFinite(page.getUserUnit()) || page.getUserUnit() <= 0) fail("PDF_002");
            pixels(page, 72);
        }
    }
    private static void pixels(PDPage page, int dpi) throws IOException {
        PDRectangle box = page.getCropBox();
        double width = box.getWidth() * page.getUserUnit() * dpi / 72d, height = box.getHeight() * page.getUserUnit() * dpi / 72d;
        if (!Double.isFinite(width * height) || width <= 0 || height <= 0 || width * height > 30_000_000 || Math.max(width, height) > 16000) fail("PDF_003");
    }
    private static PDPage copy(PDDocument target, PDPage page) throws IOException {
        PDPage copied = target.importPage(page); if (page.getResources() != null) copied.setResources(new PDResources(new org.apache.pdfbox.multipdf.PDFCloneUtility(target) { }.cloneForNewDocument(page.getResources().getCOSObject()))); copied.setRotation(page.getRotation());
        copied.setUserUnit(page.getUserUnit()); copied.getCOSObject().removeItem(COSName.AA); return copied;
    }
    private static void save(PDDocument target, Path path) throws IOException {
        if (target.getNumberOfPages() < 1 || target.getNumberOfPages() > MAX_PAGES) fail("PDF_003");
        target.save(path.toFile());
    }
    private static List<Integer> selection(JsonNode ranges, int count) throws IOException {
        TreeSet<Integer> pages = new TreeSet<>();
        if (ranges.isMissingNode() || ranges.isNull()) { for (int i = 0; i < count; i++) pages.add(i); }
        else {
            if (!ranges.isArray() || ranges.isEmpty() || ranges.size() > 1000) fail("PDF_001");
            for (JsonNode range : ranges) {
                if (!range.isArray() || range.size() != 2 || !range.get(0).isIntegralNumber() || !range.get(1).isIntegralNumber()) fail("PDF_001");
                int start = range.get(0).asInt(), end = range.get(1).asInt();
                if (start < 1 || end < start || end > count) fail("PDF_001");
                for (int i = start; i <= end; i++) pages.add(i - 1);
            }
        }
        return List.copyOf(pages);
    }
    private static BufferedImage readImage(Path path) throws Exception {
        try (var stream = ImageIO.createImageInputStream(path.toFile())) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) { fail("PDF_002"); }
            var reader = readers.next();
            try {
                reader.setInput(stream);
                if (!Set.of("JPEG", "PNG").contains(reader.getFormatName().toUpperCase(Locale.ROOT)) ||
                        (long) reader.getWidth(0) * reader.getHeight(0) > 30_000_000) fail("PDF_003");
                BufferedImage image = reader.read(0);
                int orientation = 1;
                var directory = ImageMetadataReader.readMetadata(path.toFile()).getFirstDirectoryOfType(ExifIFD0Directory.class);
                if (directory != null && directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) orientation = directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
                int width = image.getWidth(), height = image.getHeight();
                BufferedImage normalized = new BufferedImage(orientation >= 5 ? height : width, orientation >= 5 ? width : height, BufferedImage.TYPE_INT_RGB);
                // 将八种 EXIF 方向映射到正常阅读方向，同时合成白色背景。
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    int dx = x, dy = y;
                    switch (orientation) {
                        case 2 -> dx = width - 1 - x;
                        case 3 -> { dx = width - 1 - x; dy = height - 1 - y; }
                        case 4 -> dy = height - 1 - y;
                        case 5 -> { dx = y; dy = x; }
                        case 6 -> { dx = height - 1 - y; dy = x; }
                        case 7 -> { dx = height - 1 - y; dy = width - 1 - x; }
                        case 8 -> { dx = y; dy = width - 1 - x; }
                        default -> { }
                    }
                    Color c = new Color(image.getRGB(x, y), true); int a = c.getAlpha();
                    normalized.setRGB(dx, dy, new Color((c.getRed()*a+255*(255-a))/255,(c.getGreen()*a+255*(255-a))/255,(c.getBlue()*a+255*(255-a))/255).getRGB());
                }
                return normalized;
            } finally { reader.dispose(); }
        }
    }
    private static String mime(Path file) {
        String name = file.toString(); return name.endsWith(".pdf") ? "application/pdf" : name.endsWith(".png") ? "image/png" : name.endsWith(".jpg") ? "image/jpeg" : "text/plain";
    }
    private static void fail(String code) throws IOException { throw new IOException(code); }
}
