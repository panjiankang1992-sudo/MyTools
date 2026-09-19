package com.yuyutian.mytools.image.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.image.common.ImageException;
import com.yuyutian.mytools.image.model.ImageModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.http.HttpMethod;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ImageServiceTest {
 @TempDir Path root;
 ImageService service;ImageStore store;ImageUpstream upstream;MockEnvironment env;
 ObjectMapper json=new ObjectMapper();
 @BeforeEach void setup()throws Exception {
  var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
  new ResourceDatabasePopulator(new FileSystemResource("db/migrations/V1__create_image_jobs.sql")).execute(ds);
  store=new ImageStore(new JdbcTemplate(ds),json);upstream=mock(ImageUpstream.class);
  env=new MockEnvironment().withProperty("image.root",root.toString()).withProperty("image.local-validated","true").withProperty("image.gpu-coordination-validated","true");
  service=new ImageService(store,upstream,env);
 }
 Create request(String prompt){return new Create("krea2-local",prompt,"TEXT_TO_IMAGE",List.of(),"1024x1024",1,42,"request_1234");}
 byte[] png()throws Exception {var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",bytes);return bytes.toByteArray();}
 @Test void repositoryProxySupportsCreationAndCancellation()throws Exception {
  var factory=new org.springframework.aop.framework.ProxyFactory(store);
  factory.setProxyTargetClass(true);
  factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> invocation.proceed());
  var proxied=(ImageStore)factory.getProxy();
  var actual=new ImageService(proxied,upstream,env);
  var job=actual.create(1,request("proxy test"));
  assertEquals("QUEUED",job.path("status").asText());
  assertEquals("CANCELLED",actual.cancel(1,job.path("id").asText()).path("status").asText());
 }
 @Test void elapsedIncludesQueueAndStopsAtTerminalTime(){
  String id=service.create(1,request("timing")).path("id").asText();
  var start=java.sql.Timestamp.valueOf("2026-01-01 00:00:00");
  var end=java.sql.Timestamp.valueOf("2026-01-01 00:01:23");
  store.jdbc().update("UPDATE image_job SET created_at=?,updated_at=?,status='SUCCEEDED' WHERE id=?",start,end,id);
  assertEquals(83000,service.get(1,id).path("elapsedMillis").asLong());
  assertEquals(83000,service.list(1,0).getFirst().path("elapsedMillis").asLong());
  store.jdbc().update("UPDATE image_job SET status='RUNNING' WHERE id=?",id);
  assertTrue(service.get(1,id).path("elapsedMillis").asLong()>83000);
  store.jdbc().update("UPDATE image_job SET status='CANCELLED',updated_at=? WHERE id=?",start,id);
  assertEquals(0,service.get(1,id).path("elapsedMillis").asLong());
 }
 @Test void multipleImagesUseDistinctSourcesAndPreserveFirstImageReplay()throws Exception {
  var input=new Create("krea2-local","multi","TEXT_TO_IMAGE",List.of(),"1024x1024",2,42,"multi_image_test");
  String id=service.create(1,input).path("id").asText(),task=UUID.randomUUID().toString();
  when(upstream.scheduler(eq("/api/v1/task-instances"),eq(HttpMethod.POST),any())).thenReturn(json.readTree("{\"id\":\""+task+"\"}"));
  when(upstream.scheduler("/api/v1/task-instances/"+task,HttpMethod.GET,null)).thenReturn(json.readTree("{\"status\":\"SUCCEEDED\"}"));
  Files.createDirectories(root.resolve("outputs/"+id));
  Files.write(root.resolve("outputs/"+id+"/0.png"),png());Files.write(root.resolve("outputs/"+id+"/1.png"),png());
  var sources=new java.util.HashSet<String>();
  when(upstream.asset(any())).thenAnswer(invocation->{
   var body=json.valueToTree(invocation.getArgument(0));
   assertTrue(sources.add(body.path("sourceBusinessId").asText()),"Each image requires a unique asset source");
   return json.readTree("{\"id\":\""+UUID.randomUUID()+"\"}");
  });
  service.reconcile();
  assertEquals(Set.of(id,id+":1"),sources);
  assertEquals("SUCCEEDED",service.get(1,id).path("status").asText());
  assertEquals(2,service.get(1,id).path("result").path("images").size());
 }
 @Test void disabledModelRejectsCreation(){env.setProperty("image.local-validated","false");assertThrows(ImageException.class,()->service.create(1,request("test")));assertTrue(service.list(1,0).isEmpty());}
 @Test void idempotencyReplaysAfterGateChangesButRejectsDifferentPrompt(){var first=service.create(1,request("test"));env.setProperty("image.local-validated","false");assertEquals(first.path("id"),service.create(1,request("test")).path("id"));assertThrows(ImageException.class,()->service.create(1,request("changed")));}
 @Test void ownerIsolationCoversUploadsJobsAndReferences()throws Exception {var upload=service.upload(1,new Upload(Base64.getEncoder().encodeToString(png())));assertThrows(ImageException.class,()->service.input(2,upload.id().toString()));var job=service.create(1,request("test"));assertThrows(ImageException.class,()->service.get(2,job.path("id").asText()));assertThrows(ImageException.class,()->service.cancel(2,job.path("id").asText()));assertTrue(service.list(2,0).isEmpty());}
 @Test void invalidAndOversizedImagesAreRejected(){assertThrows(ImageException.class,()->service.upload(1,new Upload("not base64")));assertThrows(ImageException.class,()->service.upload(1,new Upload(Base64.getEncoder().encodeToString(new byte[5*1024*1024+1]))));}
 @Test void unsupportedReferenceModeRejected(){assertThrows(ImageException.class,()->service.create(1,new Create("krea2-local","test","TEXT_TO_IMAGE",List.of(UUID.randomUUID()),"1024x1024",1,42,"request_1234")));}
 @Test void cancelWithUncertainDispatchRetainsReconciliation()throws Exception {
  String id=service.create(1,request("test")).path("id").asText();
  when(upstream.scheduler(eq("/api/v1/task-instances"),eq(HttpMethod.POST),any())).thenThrow(new RuntimeException("timeout"));service.reconcile();
  assertEquals("CANCEL_REQUESTED",service.cancel(1,id).path("status").asText());
  String task=UUID.randomUUID().toString();
  when(upstream.scheduler(eq("/api/v1/task-instances"),eq(HttpMethod.POST),any())).thenReturn(json.readTree("{\"id\":\""+task+"\"}"));
  when(upstream.scheduler("/api/v1/task-instances/"+task,HttpMethod.GET,null)).thenReturn(json.readTree("{\"status\":\"CANCELLED\"}"));service.reconcile();
  verify(upstream).scheduler(eq("/api/v1/task-instances/"+task+"/cancel"),eq(HttpMethod.POST),any());assertEquals("CANCELLED",service.get(1,id).path("status").asText());
 }
 @Test void assetFailureRecoversAndOnlyPublishedImagesDownload()throws Exception {
  String id=service.create(1,request("test")).path("id").asText(),task=UUID.randomUUID().toString();
  when(upstream.scheduler(eq("/api/v1/task-instances"),eq(HttpMethod.POST),any())).thenReturn(json.readTree("{\"id\":\""+task+"\"}"));
  when(upstream.scheduler("/api/v1/task-instances/"+task,HttpMethod.GET,null)).thenReturn(json.readTree("{\"status\":\"SUCCEEDED\"}"));
  Files.createDirectories(root.resolve("outputs/"+id));Files.write(root.resolve("outputs/"+id+"/0.png"),png());
  when(upstream.asset(any())).thenThrow(new RuntimeException("offline"));service.reconcile();assertEquals("PERSISTING",service.get(1,id).path("status").asText());assertThrows(ImageException.class,()->service.image(1,id,0));
  doReturn(json.readTree("{\"id\":\""+UUID.randomUUID()+"\"}")).when(upstream).asset(any());service.reconcile();assertEquals("SUCCEEDED",service.get(1,id).path("status").asText());assertArrayEquals(png(),service.image(1,id,0));assertThrows(ImageException.class,()->service.image(2,id,0));
  verify(upstream,times(1)).scheduler(eq("/api/v1/task-instances"),eq(HttpMethod.POST),any());
 }
 @Test void symlinkCannotEscapeRoot()throws Exception {var upload=service.upload(1,new Upload(Base64.getEncoder().encodeToString(png())));var file=root.resolve("inputs/"+upload.id());Files.delete(file);Files.createSymbolicLink(file,Path.of("/etc/hosts"));assertThrows(ImageException.class,()->service.input(1,upload.id().toString()));}
 @Test void httpContractRequiresOwnerTokenAndValidatesBody()throws Exception {
  env.setProperty("image.token","test-only-token");
  var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new com.yuyutian.mytools.image.controller.ImageController(service,env)).build();
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/internal/v1/images/models").header("Authorization","Bearer wrong").header("X-Owner-Id","1")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/v1/images/jobs").header("Authorization","Bearer test-only-token").header("X-Owner-Id","1").contentType("application/json").content("{}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/v1/images/jobs").header("Authorization","Bearer test-only-token").header("X-Owner-Id","1").contentType("application/json").content(json.writeValueAsString(request("test")))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("QUEUED"));
 }
 @Test void remoteCapabilityRemainsUnavailableUntilModelValidated(){
  assertThrows(ImageException.class,()->service.create(1,new Create("sillytraven-remote","test","TEXT_TO_IMAGE",List.of(),"1024x1024",1,42,"remote_test")));
  env.setProperty("image.remote-validated","true");env.setProperty("image.remote-model","verified-model");
  var job=service.create(1,new Create("sillytraven-remote","test","TEXT_TO_IMAGE",List.of(),"1024x1024",1,42,"remote_test"));assertEquals("verified-model",job.path("request").path("modelId").asText());
 }
 @Test void slowDispatchDoesNotBlockCancellationOrOverwriteIt()throws Exception {
  String id=service.create(1,request("test")).path("id").asText(),task=UUID.randomUUID().toString();
  var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
  when(upstream.scheduler(eq("/api/v1/task-instances"),eq(HttpMethod.POST),any())).thenAnswer(invocation->{entered.countDown();release.await(3,java.util.concurrent.TimeUnit.SECONDS);return json.readTree("{\"id\":\""+task+"\"}");});
  when(upstream.scheduler("/api/v1/task-instances/"+task,HttpMethod.GET,null)).thenReturn(json.readTree("{\"status\":\"RUNNING\"}"));
  var future=java.util.concurrent.CompletableFuture.runAsync(service::reconcile);
  try{assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),()->service.cancel(1,id));}finally{release.countDown();}
  future.get(3,java.util.concurrent.TimeUnit.SECONDS);assertEquals("CANCEL_REQUESTED",service.get(1,id).path("status").asText());
 }
 @Test void cancellingBeforeDispatchDoesNotCreateSchedulerTask(){String id=service.create(1,request("test")).path("id").asText();assertEquals("CANCELLED",service.cancel(1,id).path("status").asText());service.reconcile();verifyNoInteractions(upstream);}
 @Test void lostSubmissionResponseRemainsUnconfirmedInsteadOfOrdinaryFailure()throws Exception {
  String id=service.create(1,request("test")).path("id").asText(),task=UUID.randomUUID().toString();
  when(upstream.scheduler(eq("/api/v1/task-instances"),eq(HttpMethod.POST),any())).thenReturn(json.readTree("{\"id\":\""+task+"\"}"));
  when(upstream.scheduler("/api/v1/task-instances/"+task,HttpMethod.GET,null)).thenReturn(json.readTree("{\"status\":\"FAILED\"}"));
  Path output=root.resolve("outputs/"+id);Files.createDirectories(output);Files.writeString(output.resolve("0.submission.json"),"{\"submissionStarted\":true}");service.reconcile();
  assertEquals("UNCONFIRMED",service.get(1,id).path("status").asText());assertTrue(service.get(1,id).path("result").path("unconfirmed").asBoolean());
 }
 @Test void imageToImageRequiresValidatedWorkflowAndExactlyOneOwnedInput()throws Exception {
  var upload=service.upload(1,new Upload(Base64.getEncoder().encodeToString(png())));
  var edit=new Create("krea2-local","Change the lighting","IMAGE_TO_IMAGE",List.of(upload.id()),"1024x1024",1,42,"edit_request");
  assertThrows(ImageException.class,()->service.create(1,edit));
  env.setProperty("image.edit-validated","true");env.setProperty("image.edit-workflow-revision","edit-tested-v2");
  assertThrows(ImageException.class,()->service.create(2,edit));
  assertThrows(ImageException.class,()->service.create(1,new Create("krea2-local","test","IMAGE_TO_IMAGE",List.of(),"1024x1024",1,42,"empty_edit")));
  var job=service.create(1,edit);
  assertEquals("edit-tested-v2",job.path("request").path("workflowRevision").asText());
  assertEquals(upload.id().toString(),job.path("request").path("references").get(0).asText());
 }
 @Test void promptExtractionIsIsolatedAndPersistedWithoutImageAssets()throws Exception {
  var upload=service.upload(1,new Upload(Base64.getEncoder().encodeToString(png())));
  var request=new Create("vision-local","Describe the image","IMAGE_TO_PROMPT",List.of(upload.id()),"1024x1024",1,0,"prompt_request");
  assertThrows(ImageException.class,()->service.create(1,request));
  env.setProperty("image.prompt-validated","true");
  assertThrows(ImageException.class,()->service.create(2,request));
  String id=service.create(1,request).path("id").asText(),task=UUID.randomUUID().toString();
  when(upstream.scheduler(eq("/api/v1/task-instances"),eq(HttpMethod.POST),any())).thenReturn(json.createObjectNode().put("id",task));
  when(upstream.scheduler("/api/v1/task-instances/"+task,HttpMethod.GET,null)).thenReturn(json.createObjectNode().put("status","SUCCEEDED"));
  Files.createDirectories(root.resolve("outputs/"+id));
  Files.writeString(root.resolve("outputs/"+id+"/prompt.json"),"{\"prompt\":\"A sunlit forest in watercolor\"}");
  service.reconcile();
  assertEquals("SUCCEEDED",service.get(1,id).path("status").asText());
  assertEquals("A sunlit forest in watercolor",service.get(1,id).path("result").path("prompt").asText());
  verify(upstream,never()).asset(any());
  assertThrows(ImageException.class,()->service.get(2,id));
  assertThrows(ImageException.class,()->service.image(1,id,0));
 }

}
