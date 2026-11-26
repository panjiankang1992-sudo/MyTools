package com.yuyutian.mytools.pdf.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.pdf.common.*;
import java.util.*;
/** 不同操作的严格参数边界，APP 与 MCP 使用相同规则。 */
public final class PdfRules {
 public static final Set<String> OPERATIONS=Set.of("PDF_PAGE_CUT","PDF_SPLIT","PDF_MERGE","IMAGES_TO_PDF","TEXT_TO_PDF","MARKDOWN_TO_PDF","PDF_TO_IMAGES","PDF_TO_TEXT");
 private PdfRules() { }
 /** 校验字段白名单。 */
 public static void fields(JsonNode node,String... allowed) {if(!node.isObject())bad();Set<String> names=Set.of(allowed);node.fieldNames().forEachRemaining(n->{if(!names.contains(n))bad();});}
 /** 校验请求及各操作专属参数。 */
 public static void plan(JsonNode body) {
  fields(body,"operation","inputIds","content","options","output","clientRevision","attachmentIds");
  String op=body.path("operation").asText();if(!OPERATIONS.contains(op))bad();
  if(body.has("clientRevision"))integer(body,"clientRevision",0,Integer.MAX_VALUE);
  JsonNode options=body.path("options");if(!options.isObject())bad();
  switch(op) {
   case "PDF_PAGE_CUT" -> {fields(options,"axis","position","reverse","ranges");choice(options,"axis","VERTICAL","HORIZONTAL");number(options,"position",.05,.95);bool(options,"reverse");}
   case "PDF_SPLIT" -> {fields(options,"ranges","groups","pagesPerFile");if(options.has("groups")&&(options.has("pagesPerFile")||options.has("ranges")))bad();integer(options,"pagesPerFile",1,1000);if(options.has("groups")){if(!options.get("groups").isArray()||options.get("groups").isEmpty()||options.get("groups").size()>100)bad();for(JsonNode ranges:options.get("groups"))ranges(ranges);}}
   case "PDF_MERGE" -> fields(options);
   case "PDF_TO_TEXT" -> fields(options,"ranges");
   case "PDF_TO_IMAGES" -> {fields(options,"ranges","dpi","format");integer(options,"dpi",72,300);choice(options,"format","PNG","JPG");}
   case "IMAGES_TO_PDF" -> {fields(options,"paperSize","orientation","fit","marginMm");choice(options,"paperSize","A4","A5");choice(options,"orientation","AUTO","PORTRAIT","LANDSCAPE");choice(options,"fit","FIT","FILL");number(options,"marginMm",0,50);}
   default -> {fields(options,"paperSize","pageNumbers","fontPreset","marginMm");choice(options,"paperSize","A4","A5","Letter");choice(options,"fontPreset","CJK_SANS");bool(options,"pageNumbers");number(options,"marginMm",5,50);}
  }
  if(options.has("ranges"))ranges(options.get("ranges"));
  if(body.has("output")){fields(body.get("output"),"name");name(body.get("output").path("name").asText());}
  if(body.has("attachmentIds")){JsonNode attachments=body.get("attachmentIds");if(!op.equals("MARKDOWN_TO_PDF")||!attachments.isArray()||attachments.size()>20)bad();for(JsonNode id:attachments)try{UUID.fromString(id.asText());}catch(Exception e){bad();}}
  boolean text=op.equals("TEXT_TO_PDF")||op.equals("MARKDOWN_TO_PDF");
  if(body.has("content")) {
   if(!text||body.has("inputIds"))bad();fields(body.get("content"),"format","text");
   if(!body.path("content").path("format").asText().equals(op.equals("TEXT_TO_PDF")?"TEXT":"MARKDOWN"))bad();
   JsonNode value=body.path("content").path("text");if(!value.isTextual()||value.asText().isBlank()||value.asText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144)bad();
  } else {
   JsonNode ids=body.path("inputIds");if(!ids.isArray()||ids.isEmpty()||ids.size()>100)bad();
   if(!Set.of("PDF_MERGE","IMAGES_TO_PDF").contains(op)&&ids.size()!=1)bad();
   if(op.equals("PDF_MERGE")&&ids.size()<2)bad();
   for(JsonNode id:ids)try {UUID.fromString(id.asText());}catch(Exception e){bad();}
  }
 }
 /** 校验安全显示文件名。 */
 public static String name(String value) {if(value.isBlank()||value.length()>180||value.contains("/")||value.contains("\\")||value.chars().anyMatch(c->c<32))bad();return value;}
 private static void ranges(JsonNode value){if(!value.isArray()||value.isEmpty()||value.size()>1000)bad();for(JsonNode r:value){if(!r.isArray()||r.size()!=2||!r.get(0).isInt()||!r.get(1).isInt()||r.get(0).asInt()<1||r.get(1).asInt()<r.get(0).asInt()||r.get(1).asInt()>1000)bad();}}
 private static void choice(JsonNode o,String key,String... values){if(o.has(key)&&(!o.get(key).isTextual()||!Set.of(values).contains(o.get(key).asText())))bad();}
 private static void integer(JsonNode o,String key,int min,int max){if(o.has(key)&&(!o.get(key).isInt()||o.get(key).asInt()<min||o.get(key).asInt()>max))bad();}
 private static void number(JsonNode o,String key,double min,double max){if(o.has(key)&&(!o.get(key).isNumber()||!Double.isFinite(o.get(key).asDouble())||o.get(key).asDouble()<min||o.get(key).asDouble()>max))bad();}
 private static void bool(JsonNode o,String key){if(o.has(key)&&!o.get(key).isBoolean())bad();}
 private static void bad(){throw new PdfException(ErrorCode.PDF_001);}
}
