package com.stonewu.fusion.service.generation.strategy.impl.openaivideo;

import com.stonewu.fusion.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容视频协议路由器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpenAiCompatibleVideoProtocolRouter {

    private final List<OpenAiCompatibleVideoProtocolAdapter> adapters;

    private volatile Map<String, OpenAiCompatibleVideoProtocolAdapter> adapterMap;

    public OpenAiCompatibleVideoProtocolAdapter resolve(OpenAiCompatibleVideoProtocolContext context) {
        Map<String, OpenAiCompatibleVideoProtocolAdapter> map = getAdapterMap();
        String protocol = context.metadata() != null ? context.metadata().effectiveProtocol() : "generic";
        OpenAiCompatibleVideoProtocolAdapter adapter = map.get(protocol);
        if (adapter != null) {
            return adapter;
        }

        if (!"generic".equals(protocol)) {
            log.warn("[OpenAI Video] 未找到协议适配器，回退到通用协议: protocol={}, model={}",
                    protocol, context.model() != null ? context.model().getCode() : null);
        }

        adapter = map.get("generic");
        if (adapter != null) {
            return adapter;
        }

        throw new BusinessException("OpenAI 兼容视频缺少通用协议适配器");
    }

    private Map<String, OpenAiCompatibleVideoProtocolAdapter> getAdapterMap() {
        if (adapterMap == null) {
            synchronized (this) {
                if (adapterMap == null) {
                    Map<String, OpenAiCompatibleVideoProtocolAdapter> resolved = new LinkedHashMap<>();
                    for (OpenAiCompatibleVideoProtocolAdapter adapter : adapters) {
                        resolved.putIfAbsent(adapter.getProtocol(), adapter);
                    }
                    adapterMap = resolved;
                }
            }
        }
        return adapterMap;
    }
}