package com.stonewu.fusion.service.generation.strategy.impl.openaivideo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import okhttp3.RequestBody;

/**
 * OpenAI 兼容通用视频协议适配器，默认按官方 Sora Videos API 处理。
 */
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleGenericVideoProtocolAdapter implements OpenAiCompatibleVideoProtocolAdapter {

    private final OpenAiCompatibleVideoProtocolSupport support;

    @Override
    public String getProtocol() {
        return "generic";
    }

    @Override
    public String resolveSubmitUrl(OpenAiCompatibleVideoProtocolContext context) {
        return support.resolveOpenAiVideosUrl(context.apiConfig());
    }

    @Override
    public RequestBody buildSubmitBody(OpenAiCompatibleVideoProtocolContext context) {
        return support.buildSoraSubmitBody(context);
    }

    @Override
    public OpenAiCompatibleVideoTaskResult parseSubmitResponse(OpenAiCompatibleVideoProtocolContext context,
                                                               String responseBody) {
        return parseResult(responseBody);
    }

    @Override
    public String resolveQueryUrl(OpenAiCompatibleVideoProtocolContext context, String trackingId) {
        return support.resolveOpenAiVideosUrl(context.apiConfig()) + "/" + trackingId;
    }

    @Override
    public OpenAiCompatibleVideoTaskResult parseQueryResponse(OpenAiCompatibleVideoProtocolContext context,
                                                              String responseBody) {
        return parseResult(responseBody);
    }

    @Override
    public String resolveVideoContentUrl(OpenAiCompatibleVideoProtocolContext context,
                                         String trackingId,
                                         OpenAiCompatibleVideoTaskResult result) {
        return support.resolveOpenAiVideosUrl(context.apiConfig()) + "/" + trackingId + "/content";
    }

    @Override
    public String resolveCoverContentUrl(OpenAiCompatibleVideoProtocolContext context,
                                         String trackingId,
                                         OpenAiCompatibleVideoTaskResult result) {
        return support.resolveOpenAiVideosUrl(context.apiConfig()) + "/" + trackingId + "/content?variant=thumbnail";
    }

    private OpenAiCompatibleVideoTaskResult parseResult(String responseBody) {
        JsonNode root = support.readJson(responseBody, "OpenAI 视频响应不是合法 JSON");
        JsonNode node = root;
        JsonNode data = root.path("data");
        if (data.isArray() && !data.isEmpty()) {
            node = data.get(0);
        } else if (data.isObject()) {
            node = data;
        }

        String trackingId = support.firstText(node, "id", "video_id", "task_id");
        String status = support.firstText(node, "status", "state");
        Integer duration = support.parsePositiveSeconds(support.firstText(node, "seconds", "duration"));
        String videoUrl = support.firstText(node, "url", "video_url");
        String coverUrl = support.firstText(node, "cover_url", "thumbnail_url", "coverUrl", "thumbnailUrl");
        String errorMessage = support.extractErrorMessage(root);
        return new OpenAiCompatibleVideoTaskResult(trackingId, status, duration, videoUrl, coverUrl, errorMessage);
    }
}