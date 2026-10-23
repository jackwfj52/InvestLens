package org.jack.backend.common.json;

import org.jack.backend.common.exception.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;

@Component
public class ContractSchemas {
    private final ObjectMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final JsonNode definitions;
    public ContractSchemas() {
        try{definitions=json.readTree(new ClassPathResource("contracts/contract.schema.json").getInputStream()).get("$defs");}
        catch(java.io.IOException missing){throw new IllegalStateException("Contract schema unavailable");}
    }
    public JsonNode parse(String content) {
        try{if(content==null || content.length()>131072)throw new IllegalArgumentException();return json.readTree(content);}
        catch(RuntimeException invalid){throw new BusinessException(ErrorCode.MODEL_OUTPUT_INVALID);}
    }
    public void require(String name,JsonNode value) {
        JsonNode schema=definitions.get(name);
        if(schema==null || !matches(value,schema,0))throw new BusinessException(ErrorCode.MODEL_OUTPUT_INVALID);
    }
    private boolean matches(JsonNode value,JsonNode schema,int depth) {
        if(depth>100 || value==null)return false;
        if(schema.has("$ref")) {
            String ref=schema.get("$ref").asString();if(!ref.startsWith("#/$defs/"))return false;
            JsonNode target=definitions.get(ref.substring(8));return target!=null && matches(value,target,depth+1);
        }
        if(schema.has("const") && !value.equals(schema.get("const")))return false;
        if(schema.has("enum")){boolean found=false;for(JsonNode item:schema.get("enum"))found|=value.equals(item);if(!found)return false;}
        if(schema.has("anyOf")){boolean found=false;for(JsonNode item:schema.get("anyOf"))found|=matches(value,item,depth+1);if(!found)return false;}
        if(schema.has("oneOf")){int count=0;for(JsonNode item:schema.get("oneOf"))if(matches(value,item,depth+1))count++;if(count!=1)return false;}
        if(schema.has("allOf"))for(JsonNode item:schema.get("allOf"))if(!matches(value,item,depth+1))return false;
        if(schema.has("not") && matches(value,schema.get("not"),depth+1))return false;
        if(schema.has("if")){String branch=matches(value,schema.get("if"),depth+1)?"then":"else";if(schema.has(branch) && !matches(value,schema.get(branch),depth+1))return false;}
        if(schema.has("type")) {
            boolean valid=switch(schema.get("type").asString()) {
                case "object"->value.isObject();case "array"->value.isArray();case "string"->value.isString();case "integer"->value.isIntegralNumber();
                case "number"->value.isNumber();case "boolean"->value.isBoolean();case "null"->value.isNull();default->false;
            };if(!valid)return false;
        }
        if(value.isObject()) {
            if(schema.has("required"))for(JsonNode key:schema.get("required"))if(!value.has(key.asString()))return false;
            JsonNode properties=schema.path("properties");
            for(var entry:value.properties()) {
                if(properties.has(entry.getKey())) {if(!matches(entry.getValue(),properties.get(entry.getKey()),depth+1))return false;}
                else if(schema.has("additionalProperties") && schema.get("additionalProperties").isBoolean() && !schema.get("additionalProperties").asBoolean())return false;
            }
        }
        if(value.isArray()) {
            if(schema.has("minItems") && value.size()<schema.get("minItems").asInt())return false;
            if(schema.has("maxItems") && value.size()>schema.get("maxItems").asInt())return false;
            if(schema.path("uniqueItems").asBoolean(false)){var seen=new HashSet<JsonNode>();for(JsonNode item:value)if(!seen.add(item))return false;}
            if(schema.has("items"))for(JsonNode item:value)if(!matches(item,schema.get("items"),depth+1))return false;
        }
        if(value.isString()) {
            String text=value.asString();int length=text.codePointCount(0,text.length());
            if(schema.has("minLength") && length<schema.get("minLength").asInt())return false;
            if(schema.has("maxLength") && length>schema.get("maxLength").asInt())return false;
            if(schema.has("pattern") && !java.util.regex.Pattern.compile(schema.get("pattern").asString()).matcher(text).find())return false;
            if(schema.has("format"))try{switch(schema.get("format").asString()){case "date"->LocalDate.parse(text);case "date-time"->OffsetDateTime.parse(text);default->{}}}catch(RuntimeException invalid){return false;}
        }
        if(value.isNumber()) {
            if(schema.has("minimum") && value.decimalValue().compareTo(schema.get("minimum").decimalValue())<0)return false;
            if(schema.has("maximum") && value.decimalValue().compareTo(schema.get("maximum").decimalValue())>0)return false;
        }
        return true;
    }
}
