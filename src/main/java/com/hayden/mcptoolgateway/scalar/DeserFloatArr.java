package com.hayden.mcptoolgateway.scalar;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

public class DeserFloatArr extends JsonDeserializer<FloatArray> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public FloatArray deserialize(JsonParser jsonParser,
                                  DeserializationContext deserializationContext) throws IOException {
        TreeNode treeNode = jsonParser.readValueAsTree();
        var parsedValueToString = objectMapper.readValue(treeNode.traverse(objectMapper), String.class)
                .replaceAll("\"", "");
        var parsedValueToFloat = objectMapper.readValue(parsedValueToString, float[].class);
        return new FloatArray(parsedValueToFloat);
    }
}
