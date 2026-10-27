package com.school.party.domain.community.post;

import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.core.StreamReadFeature;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class PostJsonConfiguration {
    @Bean JsonMapperBuilderCustomizer strictJsonTypes() {
        return builder -> {
            builder.configure(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS, true);
            builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
            // ALLOW_COERCION_OF_SCALARS alone still accepts boolean/number -> String.
            for (CoercionInputShape shape : new CoercionInputShape[] {
                    CoercionInputShape.Boolean, CoercionInputShape.Integer, CoercionInputShape.Float }) {
                builder.withCoercionConfig(LogicalType.Textual, c -> c.setCoercion(shape, CoercionAction.Fail));
                builder.withCoercionConfig(LogicalType.DateTime, c -> c.setCoercion(shape, CoercionAction.Fail));
            }
        };
    }
}
