package com.yuyutian.mytools.pdf.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.pdf.common.PdfException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:pdf;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","spring.datasource.username=sa","spring.datasource.password=","pdf.enabled=true","pdf.text-enabled=true","pdf.reconcile-ms=999999","pdf.cleanup-ms=999999"})
class PdfServiceTest {
 static final Path root;
 static{try{root=Files.createTempDirectory("pdf-service-test-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
 @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("pdf.root",root::toString);}
 @Autowired PdfService service;
 @Autowired PdfStore store;
 @Autowired ObjectMapper json;
 @BeforeEach void reset(){store.jdbc().update("DELETE FROM pdf_record");}
 private ObjectNode upload() {return json.createObjectNode().put("name","sample.pdf").put("bytes",4).put("mediaType","application/pdf");}
 @Test void uploadRetryAndOwnershipAreEnforced()throws Exception{
  var first=service.upload(1,upload(),"upload-key-1");String id=first.path("id").asText();assertEquals(id,service.upload(1,upload(),"upload-key-1").path("id").asText());
  assertThrows(PdfException.class,()->service.get(2,id,"INPUT"));
  service.content(1,id,0,new ByteArrayInputStream(new byte[]{1,2}));service.content(1,id,0,new ByteArrayInputStream(new byte[]{1,2}));
  assertThrows(PdfException.class,()->service.content(1,id,0,new ByteArrayInputStream(new byte[]{9,2})));
  service.content(1,id,2,new ByteArrayInputStream(new byte[]{3,4}));assertEquals("QUEUED",service.complete(1,id).path("status").asText());assertEquals("QUEUED",service.complete(1,id).path("status").asText());
  assertThrows(PdfException.class,()->service.upload(1,upload().put("bytes",5),"upload-key-1"));
 }
 @Test void cancelledAndStaleWorkersCannotPublish()throws Exception{
  String id=service.upload(3,upload(),"upload-key-3").path("id").asText();service.content(3,id,0,new ByteArrayInputStream(new byte[]{1,2,3,4}));service.complete(3,id);
  String task=UUID.randomUUID().toString();store.jdbc().update("UPDATE pdf_record SET task_id=?,dispatch_started=TRUE WHERE id=?",task,id);
  service.claim(id,task,2);assertThrows(PdfException.class,()->service.claim(id,task,1));service.cancel(3,id,"INPUT");
  assertThrows(PdfException.class,()->service.finish(id,task,2,json.createObjectNode().put("pageCount",2)));
 }
 @Test void planRejectsUnknownFieldsAndInvalidGeometry(){
  ObjectNode request=json.createObjectNode().put("operation","PDF_PAGE_CUT");request.putArray("inputIds").add(UUID.randomUUID().toString());request.putObject("options").put("position",1.0);
  assertThrows(PdfException.class,()->PdfRules.plan(request));request.withObject("/options").put("position",.5).put("command","bad");assertThrows(PdfException.class,()->PdfRules.plan(request));
 }
 @Test void canonicalReplayAndFrozenPlansRespectAttachmentOwnership(){
  var original=upload();var created=service.upload(9,original,"canonical-upload");
  var reordered=json.createObjectNode().put("mediaType","application/pdf").put("bytes",4).put("name","sample.pdf");
  assertEquals(created.path("id"),service.upload(9,reordered,"canonical-upload").path("id"));
  var image=upload().put("name","image.png").put("mediaType","image/png");
  String foreign=service.upload(10,image,"foreign-image").path("id").asText();store.jdbc().update("UPDATE pdf_record SET status='READY' WHERE id=?",foreign);
  var request=json.createObjectNode().put("operation","MARKDOWN_TO_PDF");request.putObject("options");request.putObject("content").put("format","MARKDOWN").put("text","# Test");request.putArray("attachmentIds").add(foreign);
  assertThrows(PdfException.class,()->service.plan(9,request,"foreign-plan"));
  request.putArray("attachmentIds");String plan=service.plan(9,request,"own-plan").path("id").asText();
  assertThrows(PdfException.class,()->service.start(9,json.createObjectNode().put("planId",plan),"start-before-ready"));
  request.withObject("/content").put("text","changed");assertThrows(PdfException.class,()->service.plan(9,request,"own-plan"));
 }

 @Test void cleanupKeepsUnexpiredReadyPlansAndExpiresDuePlans(){
  String active=store.insert(21,"PLAN","READY",json.createObjectNode().put("operation","TEXT_TO_PDF"),"live-plan",1800);
  String due=store.insert(21,"PLAN","READY",json.createObjectNode().put("operation","TEXT_TO_PDF"),"due-plan",-60);
  service.cleanup();
  assertEquals("READY",store.owned(21,active,"PLAN").get("status"));
  assertEquals("EXPIRED",store.owned(21,due,"PLAN").get("status"));
 }

}
