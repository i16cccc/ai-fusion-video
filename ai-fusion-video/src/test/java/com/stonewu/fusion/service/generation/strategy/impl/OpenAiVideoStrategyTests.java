package com.stonewu.fusion.service.generation.strategy.impl;

import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.entity.generation.VideoItem;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.ai.ApiConfigService;
import com.stonewu.fusion.service.ai.ModelPresetService;
import com.stonewu.fusion.service.generation.VideoGenerationService;
import com.stonewu.fusion.service.storage.MediaStorageService;
import com.stonewu.fusion.service.storage.StorageConfigService;
import com.stonewu.fusion.service.system.PresetArtStyleResourceResolver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiVideoStrategyTests {

    private HttpServer server;

    private final StorageConfigService storageConfigService = mock(StorageConfigService.class);
    private final PresetArtStyleResourceResolver presetArtStyleResourceResolver = new PresetArtStyleResourceResolver();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void getNameUsesOpenAiCompatiblePlatformKey() {
        OpenAiVideoStrategy strategy = new OpenAiVideoStrategy(
                mock(AiModelService.class),
                mock(ApiConfigService.class),
                mock(VideoGenerationService.class),
                mock(ModelPresetService.class),
                mock(MediaStorageService.class),
                storageConfigService,
                presetArtStyleResourceResolver
        );

        assertThat(strategy.getName()).isEqualTo("openai_compatible");
    }

    @Test
    void submitAndPollText2VideoStoresLocalVideoContent() throws Exception {
        byte[] videoBytes = "fake-mp4-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] coverBytes = "fake-cover-bytes".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> submitPath = new AtomicReference<>();
        AtomicReference<String> submitBody = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/videos", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) && "/v1/videos".equals(path)) {
                submitPath.set(path);
                submitBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                writeJson(exchange, """
                        {"id":"video_123","object":"video","model":"sora-2","status":"queued","progress":0}
                        """);
                return;
            }
            if ("/v1/videos/video_123/content".equals(path)) {
                byte[] payload = "thumbnail".equals(query == null ? "" : query.replace("variant=", ""))
                        ? coverBytes : videoBytes;
                String contentType = payload == coverBytes ? "image/jpeg" : "video/mp4";
                writeBinary(exchange, payload, contentType);
                return;
            }
            if ("/v1/videos/video_123".equals(path)) {
                writeJson(exchange, """
                        {"id":"video_123","status":"completed","progress":100,"seconds":"4"}
                        """);
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();

        AiModel model = AiModel.builder().id(10L).code("sora-2").apiConfigId(1L).build();
        ApiConfig apiConfig = ApiConfig.builder()
                .platform("openai_compatible")
                .apiUrl("http://localhost:" + server.getAddress().getPort())
                .apiKey("test-key")
                .build();

        AiModelService aiModelService = mock(AiModelService.class);
        when(aiModelService.getById(10L)).thenReturn(model);
        ApiConfigService apiConfigService = mock(ApiConfigService.class);
        when(apiConfigService.getById(1L)).thenReturn(apiConfig);

        VideoItem item = VideoItem.builder().id(100L).taskId(5L).status(0).build();
        VideoGenerationService videoGenerationService = mock(VideoGenerationService.class);
        when(videoGenerationService.listItems(5L)).thenReturn(List.of(item));

        MediaStorageService mediaStorageService = mock(MediaStorageService.class);
        when(mediaStorageService.storeBytes(any(), eq("videos"), eq("mp4")))
                .thenReturn("/media/videos/result.mp4");
        when(mediaStorageService.storeBytes(any(), eq("images"), eq("jpg")))
                .thenReturn("/media/images/cover.jpg");

        OpenAiVideoStrategy strategy = new OpenAiVideoStrategy(
                aiModelService,
                apiConfigService,
                videoGenerationService,
                mock(ModelPresetService.class),
                mediaStorageService,
                storageConfigService,
                presetArtStyleResourceResolver
        );

        VideoTask task = VideoTask.builder().id(5L).taskId("task-1").modelId(10L).prompt("a cat playing piano")
                .duration(4).resolution("1280x720").build();

        String platformTaskId = strategy.submit(task);
        assertThat(platformTaskId).isEqualTo("video_123");
        assertThat(item.getPlatformTaskId()).isEqualTo("video_123");
        assertThat(submitPath.get()).isEqualTo("/v1/videos");
        assertThat(submitBody.get()).contains("sora-2");
        assertThat(submitBody.get()).contains("a cat playing piano");
        assertThat(submitBody.get()).contains("1280x720");

        strategy.poll(platformTaskId, task);

        assertThat(item.getVideoUrl()).isEqualTo("/media/videos/result.mp4");
        assertThat(item.getCoverUrl()).isEqualTo("/media/images/cover.jpg");
        assertThat(item.getStatus()).isEqualTo(1);
        assertThat(item.getDuration()).isEqualTo(4);
        verify(mediaStorageService).storeBytes(videoBytes, "videos", "mp4");
    }

    private static void writeJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void writeBinary(HttpExchange exchange, byte[] payload, String contentType) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }
}
