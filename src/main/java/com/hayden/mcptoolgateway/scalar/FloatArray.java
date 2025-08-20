package com.hayden.mcptoolgateway.scalar;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Data;

@AllArgsConstructor
@JsonDeserialize(using = DeserFloatArr.class)
@Data
public class FloatArray {

    float[] f;

}
