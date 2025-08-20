package com.hayden.mcptoolgateway.scalar;

import com.netflix.graphql.dgs.DgsScalar;
import graphql.language.StringValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.nio.charset.StandardCharsets;

@DgsScalar(name = "ServerByteArray")
@RequiredArgsConstructor
@Data
public class ServerByteArray implements Coercing<ByteArray, String> {

    @Override
    public String serialize(Object dataFetcherResult) throws CoercingSerializeException {
        if (dataFetcherResult instanceof ByteArray b) {
            return new String(b.f, StandardCharsets.UTF_8);
        }
        throw new CoercingSerializeException("Invalid value '" + dataFetcherResult + "' for Byte");
    }

    @Override
    public ByteArray parseValue(Object input) throws CoercingParseValueException {
        if (input instanceof String s) {
            return new ByteArray(s.getBytes(StandardCharsets.UTF_8));
        }
        throw new CoercingParseValueException("Invalid value '" + input + "' for Byte");
    }

    @Override
    public ByteArray parseLiteral(Object input) throws CoercingParseLiteralException {
        if (input instanceof StringValue s) {
            return new ByteArray(s.getValue().getBytes(StandardCharsets.UTF_8));
        }
        throw new CoercingParseLiteralException("Invalid value '" + input + "' for Byte");
    }
}
