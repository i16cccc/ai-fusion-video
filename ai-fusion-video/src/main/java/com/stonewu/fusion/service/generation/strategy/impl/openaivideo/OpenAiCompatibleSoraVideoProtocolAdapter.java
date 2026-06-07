package com.stonewu.fusion.service.generation.strategy.impl.openaivideo;

import lombok.RequiredArgsConstructor;
import okhttp3.RequestBody;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容 Sora 协议适配器。
 */
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleSoraVideoProtocolAdapter implements OpenAiCompatibleVideoProtocolAdapter {

    private final OpenAiCompatibleGenericVideoProtocolAdapter delegate;

    @Override
    public String getProtocol() {
        return "sora";
    }

    @Override
    public String resolveSubmitUrl(OpenAiCompatibleVideoProtocolContext context) {
        return delegate.resolveSubmitUrl(context);
    }

    @Override
    public RequestBody buildSubmitBody(OpenAiCompatibleVideoProtocolContext context) {
        return delegate.buildSubmitBody(context);
    }

    @Override
    public OpenAiCompatibleVideoTaskResult parseSubmitResponse(OpenAiCompatibleVideoProtocolContext context,
                                                               String responseBody) {
        return delegate.parseSubmitResponse(context, responseBody);
    }

    @Override
    public String resolveQueryUrl(OpenAiCompatibleVideoProtocolContext context, String trackingId) {
        return delegate.resolveQueryUrl(context, trackingId);
    }

    @Override
    public OpenAiCompatibleVideoTaskResult parseQueryResponse(OpenAiCompatibleVideoProtocolContext context,
                                                              String responseBody) {
        return delegate.parseQueryResponse(context, responseBody);
    }

    @Override
    public String resolveVideoContentUrl(OpenAiCompatibleVideoProtocolContext context,
                                         String trackingId,
                                         OpenAiCompatibleVideoTaskResult result) {
        return delegate.resolveVideoContentUrl(context, trackingId, result);
    }

    @Override
    public String resolveCoverContentUrl(OpenAiCompatibleVideoProtocolContext context,
                                         String trackingId,
                                         OpenAiCompatibleVideoTaskResult result) {
        return delegate.resolveCoverContentUrl(context, trackingId, result);
    }
}