package com.stonewu.fusion.service.generation.strategy.impl.openaivideo;

/**
 * OpenAI 兼容视频协议统一任务结果。
 */
public record OpenAiCompatibleVideoTaskResult(
        String trackingId,
        String status,
        Integer durationSeconds,
        String videoUrl,
        String coverUrl,
        String errorMessage
) {
}