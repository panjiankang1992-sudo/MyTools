package com.yuyutian.mytools.image.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.image.common.ImageException;
import com.yuyutian.mytools.image.common.ErrorCode;
import com.yuyutian.mytools.image.model.ImageModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.http.HttpMethod;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImageStyleServiceTest {
 @TempDir Path root;
 ImageStore store;ImageService images;ImageStyleService styles;ImageUpstream upstream;
 ObjectMapper json=new ObjectMapper();
 @BeforeEach void setup()throws Exception {
  var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
  new ResourceDatabasePopulator(new FileSystemResource("db/migrations/V1__create_image_jobs.sql"),new FileSystemResource("db/migrations/V2__create_image_styles.sql")).execute(ds);
  store=new ImageStore(new JdbcTemplate(ds),json);upstream=mock(ImageUpstream.class);
  var env=new MockEnvironment().withProperty("image.root",root.toString()).withProperty("image.local-validated","true").withProperty("image.gpu-coordination-validated","true");
  images=new ImageService(store,upstream,env);styles=images.styles();
 }
 Create styled(String id,int version,String key,UUID source){return new Create("krea2-local","a lake","TEXT_TO_IMAGE",List.of(),"1024x1024",1,42,key,id,version,source);}
 @Test void builtinCatalogHasTwentyFourCompatibleLiteralTemplates() {
  var catalog=styles.list(1);assertEquals(24,catalog.size());
  for(var style:catalog) {
   assertEquals("BUILTIN",style.path("origin").asText());
   assertTrue(styles.compose(style,"a lake","krea2-local","TEXT_TO_IMAGE").contains("a lake"));
   assertTrue(styles.compose(style,"a lake","krea2-local","IMAGE_TO_IMAGE").contains("a lake"));
  }
 }
 @Test void accountIsolationAppliesToEveryPrivateOperation() {
  UUID id=UUID.randomUUID();styles.create(1,id,new StyleWrite("Ink","{prompt}, ink",null));
  assertEquals(25,styles.list(1).size());assertEquals(24,styles.list(2).size());
  assertEquals(ErrorCode.IMAGE_004,assertThrows(ImageException.class,()->styles.resolve(2,id.toString(),1)).code());
  assertThrows(ImageException.class,()->styles.update(2,id,new StyleWrite("Other","{prompt}, blue",1)));
  assertThrows(ImageException.class,()->styles.delete(2,id));
  assertThrows(ImageException.class,()->styles.create(2,id,new StyleWrite("Ink","{prompt}, ink",null)));
  assertThrows(ImageException.class,()->images.create(2,styled(id.toString(),1,"isolated_job",null)));
 }
 @Test void creationAndUpdatesAreIdempotentAndVersionsRemainImmutable() {
  UUID id=UUID.randomUUID();var first=new StyleWrite("Ink","{prompt}, ink",null);
  assertEquals(styles.create(1,id,first),styles.create(1,id,first));
  assertThrows(ImageException.class,()->styles.create(1,id,new StyleWrite("Ink","{prompt}, red",null)));
  var edit=new StyleWrite("Blue","{prompt}, blue ink",1);
  var second=styles.update(1,id,edit);assertEquals(2,second.path("version").asInt());assertEquals(second,styles.update(1,id,edit));
  assertEquals("{prompt}, ink",styles.resolve(1,id.toString(),1).path("promptTemplate").asText());
  assertEquals(ErrorCode.IMAGE_005,assertThrows(ImageException.class,()->styles.update(1,id,new StyleWrite("Other","{prompt}, red",1))).code());
 }
 @Test void deletedStyleDoesNotBreakTaskReplayOrOwnedHistoricalReuse() {
  UUID id=UUID.randomUUID();styles.create(1,id,new StyleWrite("Ink","{prompt}, ink",null));
  var request=styled(id.toString(),1,"first_styled_job",null);var job=images.create(1,request);
  styles.update(1,id,new StyleWrite("Blue","{prompt}, blue",1));styles.delete(1,id);styles.delete(1,id);
  assertEquals(24,styles.list(1).size());assertEquals(job.path("id"),images.create(1,request).path("id"));
  assertThrows(ImageException.class,()->images.create(1,styled(id.toString(),1,"deleted_new_job",null)));
  UUID source=UUID.fromString(job.path("id").asText());
  var reuse=images.create(1,styled(id.toString(),1,"reuse_styled_job",source));
  assertEquals("a lake, ink",reuse.path("request").path("effectivePrompt").asText());
  assertThrows(ImageException.class,()->images.create(2,styled(id.toString(),1,"foreign_reuse_job",source)));
  assertThrows(ImageException.class,()->images.create(1,styled(id.toString(),2,"forged_reuse_job",source)));
 }
 @Test void schedulerReceivesOnlyEffectivePromptAndExistingContractFields()throws Exception {
  var job=images.create(1,styled("watercolor",1,"dispatch_styled_job",null));
  var parameters=new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
  String task=UUID.randomUUID().toString();
  when(upstream.scheduler(eq("/api/v1/task-instances"),eq(HttpMethod.POST),any())).thenAnswer(call->{parameters.set(json.valueToTree(call.getArgument(2)).path("parameters"));return json.readTree("{\"id\":\""+task+"\"}");});
  when(upstream.scheduler("/api/v1/task-instances/"+task,HttpMethod.GET,null)).thenReturn(json.readTree("{\"status\":\"RUNNING\"}"));
  images.reconcile();
  assertEquals(job.path("request").path("effectivePrompt").asText(),parameters.get().path("prompt").asText());
  for(String key:List.of("styleId","styleVersion","styleSnapshot","effectivePrompt","styleSourceJobId"))assertFalse(parameters.get().has(key));
  assertEquals("a lake",images.get(1,job.path("id").asText()).path("request").path("prompt").asText());
 }
 @Test void oldRequestDigestRemainsCompatible()throws Exception {
  var request=new Create("krea2-local","a lake","TEXT_TO_IMAGE",List.of(),"1024x1024",1,42,"legacy_request");
  var job=images.create(1,request);
  var old=json.valueToTree(request);((com.fasterxml.jackson.databind.node.ObjectNode)old).remove(List.of("styleId","styleVersion","styleSourceJobId"));
  String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(old)));
  assertEquals(digest,store.job(1,job.path("id").asText()).get("request_sha256"));
 }
 @Test void invalidTemplatesLengthsModesAndPartialStyleReferencesAreRejected() {
  for(String template:List.of("none","{prompt} {prompt}","{prompt} {other}","{prompt} ${expression}","{prompt}"+"x".repeat(2000)))assertThrows(ImageException.class,()->styles.create(1,UUID.randomUUID(),new StyleWrite("Test",template,null)));
  var builtin=styles.resolve(1,"watercolor",1);
  assertThrows(ImageException.class,()->styles.compose(builtin,"x".repeat(4000),"krea2-local","TEXT_TO_IMAGE"));
  assertThrows(ImageException.class,()->styles.compose(builtin,"a lake","krea2-local","IMAGE_TO_PROMPT"));
  assertThrows(ImageException.class,()->styles.compose(builtin,"a lake","sillytraven-remote","TEXT_TO_IMAGE"));
  assertThrows(ImageException.class,()->images.create(1,new Create("krea2-local","test","TEXT_TO_IMAGE",List.of(),"1024x1024",1,42,"partial_request",null,1,null)));
 }
 @Test void concurrentCreationReturnsTheSameStyle()throws Exception {
  UUID id=UUID.randomUUID();var body=new StyleWrite("Ink","{prompt}, ink",null);
  try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
   var gate=new java.util.concurrent.CountDownLatch(1);
   var first=pool.submit(()->{gate.await();return styles.create(1,id,body);});
   var second=pool.submit(()->{gate.await();return styles.create(1,id,body);});gate.countDown();
   assertEquals(first.get(5,java.util.concurrent.TimeUnit.SECONDS),second.get(5,java.util.concurrent.TimeUnit.SECONDS));
   assertEquals(25,styles.list(1).size());
  }
 }
 @Test void httpStylesRequireTrustedOwnerAndValidateVersion()throws Exception {
  var env=new MockEnvironment().withProperty("image.token","test-token");
  var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new com.yuyutian.mytools.image.controller.ImageController(images,env)).build();
  String path="/internal/v1/images/styles/"+UUID.randomUUID();
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/internal/v1/images/styles")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization","Bearer test-token").header("X-Owner-Id","1").contentType("application/json").content(json.writeValueAsString(new StyleWrite("Ink","{prompt}, ink",null)))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).header("Authorization","Bearer test-token").header("X-Owner-Id","1").contentType("application/json").content(json.writeValueAsString(new StyleWrite("Blue","{prompt}, blue",0)))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path).header("Authorization","Bearer test-token").header("X-Owner-Id","2")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
 }

}
