package com.yuyutian.mytools.task.scheduler.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.yuyutian.mytools.task.scheduler.common.ErrorCode;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * 任务参数与结果 JSON Schema 校验服务。
 */
@Service
public class TaskSchemaValidationService {

    private final ObjectMapper objectMapper;
    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    /**
     * 创建任务契约校验服务。
     *
     * @param objectMapper JSON 映射器
     */
    public TaskSchemaValidationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 校验任务参数。
     *
     * @param schema 参数 Schema
     * @param value 参数值
     */
    public void validateParameters(Map<String, Object> schema, Map<String, Object> value) {
        validate(schema, value, ErrorCode.TASK_PARAMETER_SCHEMA_INVALID, "Task parameters do not match schema");
    }

    /**
     * 校验任务结果。
     *
     * @param schema 结果 Schema
     * @param value 结果值
     */
    public void validateResult(Map<String, Object> schema, Map<String, Object> value) {
        validate(schema, value, ErrorCode.TASK_RESULT_SCHEMA_INVALID, "Task result does not match schema");
    }

    private void validate(Map<String, Object> schema, Map<String, Object> value, ErrorCode errorCode,
                          String message) {
        if (schema == null || schema.isEmpty()) {
            return;
        }
        JsonNode schemaNode = objectMapper.valueToTree(schema);
        JsonSchema compiled;
        try {
            compiled = schemaFactory.getSchema(schemaNode);
        } catch (RuntimeException exception) {
            throw new SchedulerException(errorCode, HttpStatus.UNPROCESSABLE_ENTITY,
                    "Task definition contains an invalid JSON Schema");
        }
        Set<ValidationMessage> errors = compiled.validate(objectMapper.valueToTree(value));
        if (!errors.isEmpty()) {
            String detail = errors.stream().map(ValidationMessage::getMessage).sorted().findFirst().orElse(message);
            throw new SchedulerException(errorCode, HttpStatus.UNPROCESSABLE_ENTITY,
                    message + ": " + detail);
        }
    }
}
