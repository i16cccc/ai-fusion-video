package com.stonewu.fusion.service.ai.model;

import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.service.ai.ApiConfigService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AiModelMetadataResolverTests {

    @Test
    void shouldInferNewApiSpecificProtocolFromFamilyKeywords() {
        AiModelMetadataResolver resolver = new AiModelMetadataResolver(mock(ApiConfigService.class));
        AiModel model = AiModel.builder()
                .name("即梦 Video")
                .code("jimeng-v1")
                .modelType(3)
                .build();

        AiModelMetadata metadata = resolver.resolve(model, "newapi");

        assertEquals("jimeng", metadata.modelFamily());
        assertEquals("jimeng", metadata.modelProtocol());
    }

    @Test
    void shouldFallbackToGenericProtocolForNewApiGenericVideoModel() {
        AiModelMetadataResolver resolver = new AiModelMetadataResolver(mock(ApiConfigService.class));
        AiModel model = AiModel.builder()
                .name("Generic Video")
                .code("video-model-v1")
                .modelType(3)
                .build();

        AiModelMetadata metadata = resolver.resolve(model, "newapi");

        assertEquals("generic", metadata.modelFamily());
        assertEquals("generic", metadata.modelProtocol());
    }

    @Test
    void shouldInferAgnesProtocolForOpenAiCompatibleVideoModel() {
        AiModelMetadataResolver resolver = new AiModelMetadataResolver(mock(ApiConfigService.class));
        AiModel model = AiModel.builder()
                .name("Agnes Video")
                .code("agnes-video-v2.0")
                .modelType(3)
                .build();

        AiModelMetadata metadata = resolver.resolve(model, "openai_compatible");

        assertEquals("agnes", metadata.modelFamily());
        assertEquals("agnes", metadata.modelProtocol());
    }
}