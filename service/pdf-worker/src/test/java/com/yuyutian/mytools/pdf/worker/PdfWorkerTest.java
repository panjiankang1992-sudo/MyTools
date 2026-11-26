package com.yuyutian.mytools.pdf.worker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.awt.Color;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class PdfWorkerTest {
 @TempDir Path temp;
 private final ObjectMapper json=new ObjectMapper();
 @Test void cutMatchesDisplayedLeftAndRightAtEveryRotation()throws Exception{
  for(int rotation:new int[]{0,90,180,270}){
   Path input=temp.resolve("input-"+rotation+".pdf");
   try(PDDocument doc=new PDDocument()){
    PDPage page=new PDPage(new PDRectangle(-20,10,240,160));page.setCropBox(new PDRectangle(0,20,200,120));page.setRotation(rotation);doc.addPage(page);
    try(PDPageContentStream stream=new PDPageContentStream(doc,page)){
     stream.setNonStrokingColor(Color.RED);stream.addRect(0,20,100,120);stream.fill();
     stream.setNonStrokingColor(Color.BLUE);stream.addRect(100,20,100,120);stream.fill();
    }
    doc.save(input.toFile());
   }
   Path out=temp.resolve("out-"+rotation);
   var request=json.createObjectNode().put("operation","PDF_PAGE_CUT");request.putArray("inputPaths").add(input.toString());request.putObject("options").put("axis","VERTICAL").put("position",0.5);
   PdfWorker.process(request,out);
   try(PDDocument original=Loader.loadPDF(input.toFile());PDDocument cut=Loader.loadPDF(out.resolve("result.pdf").toFile())){
    assertEquals(2,cut.getNumberOfPages());var whole=new PDFRenderer(original).renderImage(0);var renderer=new PDFRenderer(cut);
    for(int i=0;i<2;i++){var half=renderer.renderImage(i);assertEquals(whole.getWidth()/2,half.getWidth());assertEquals(whole.getHeight(),half.getHeight());for(int y=10;y<half.getHeight()-10;y+=17)for(int x=10;x<half.getWidth()-10;x+=17)assertEquals(whole.getRGB(x+i*half.getWidth(),y),half.getRGB(x,y));}
   }
  }
 }
 @Test void splitKeepsRemainderAndCreatesArchive()throws Exception{
  Path input=temp.resolve("twelve.pdf");try(PDDocument doc=new PDDocument()){for(int i=0;i<12;i++)doc.addPage(new PDPage());doc.save(input.toFile());}
  var request=json.createObjectNode().put("operation","PDF_SPLIT");request.putArray("inputPaths").add(input.toString());request.putObject("options").put("pagesPerFile",5);
  var result=PdfWorker.process(request,temp.resolve("split"));assertEquals(3,result.path("fileCount").asInt());assertEquals(12,result.path("pageCount").asInt());assertEquals(2,result.path("artifacts").get(2).path("pageCount").asInt());assertTrue(Files.exists(temp.resolve("split/results.zip")));
 }
 @Test void scannedTextFailsInsteadOfPublishingEmptySuccess()throws Exception{
  Path input=temp.resolve("scan.pdf");try(PDDocument doc=new PDDocument()){doc.addPage(new PDPage());doc.save(input.toFile());}
  var request=json.createObjectNode().put("operation","PDF_TO_TEXT");request.putArray("inputPaths").add(input.toString());request.putObject("options");
  assertEquals("PDF_013",assertThrows(java.io.IOException.class,()->PdfWorker.process(request,temp.resolve("text"))).getMessage());
 }
 @Test void mergingResourcesSurvivesClosingSourceDocuments()throws Exception{
  var request=json.createObjectNode().put("operation","PDF_MERGE");var inputs=request.putArray("inputPaths");request.putObject("options");
  for(int i=0;i<2;i++){Path p=temp.resolve("merge"+i+".pdf");try(PDDocument doc=new PDDocument()){PDPage page=new PDPage();doc.addPage(page);try(PDPageContentStream stream=new PDPageContentStream(doc,page)){stream.beginText();stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);stream.newLineAtOffset(20,20);stream.showText("Page "+i);stream.endText();}doc.save(p.toFile());}inputs.add(p.toString());}
  assertEquals(2,PdfWorker.process(request,temp.resolve("merged")).path("pageCount").asInt());
 }
}
