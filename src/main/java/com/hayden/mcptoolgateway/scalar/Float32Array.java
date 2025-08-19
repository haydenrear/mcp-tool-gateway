package com.hayden.mcptoolgateway.scalar;

import com.netflix.graphql.dgs.DgsScalar;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import org.springframework.context.annotation.Primary;

@DgsScalar(name = "Float32Array")
@Primary
public class Float32Array implements Coercing<FloatArray, String> {

    @Override
    public String serialize(Object dataFetcherResult) throws CoercingSerializeException {
        StringBuilder builder = new StringBuilder();
        builder = builder.append("[");
        if (dataFetcherResult instanceof FloatArray arr) {
            var f = arr.f;
            for (int i = 0; i < f.length; i++) {
                builder = builder.append(f[i]);
                if (i < f.length - 1) {
                    builder = builder.append(",");
                }
            }
        }

        return builder.append("]").toString();
    }

    @Override
    public FloatArray parseValue(Object input) throws CoercingParseValueException {
        if (input instanceof String) {
            return parseFloatArray((String) input);
        }
        throw new CoercingParseValueException("Expected a string.");
    }

    @Override
    public FloatArray parseLiteral(Object input) throws CoercingParseLiteralException {
        if (input instanceof String) {
            return parseFloatArray((String) input);
        }
        throw new CoercingParseLiteralException("Expected a string.");
    }

    private FloatArray parseFloatArray(String input) {
        String[] parts = input.split(",");
        float[] floatArray = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                floatArray[i] = Float.parseFloat(parts[i]);
            } catch (NumberFormatException e) {
                throw new CoercingParseValueException("Invalid float format: " + input);
            }
        }
        return new FloatArray(floatArray);
    }
}
