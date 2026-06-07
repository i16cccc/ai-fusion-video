package com.stonewu.fusion.service.ai.model;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.service.ai.ApiConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 统一解析模型语义元数据，避免渠道、家族、协议在多个调用点重复推断。
 */
@Service
@RequiredArgsConstructor
public class AiModelMetadataResolver {

    private final ApiConfigService apiConfigService;

    public AiModelMetadata resolve(AiModel model) {
        return resolve(model, resolvePlatform(model));
    }

    public AiModelMetadata resolve(AiModel model, String platform) {
        String normalizedPlatform = normalizePlatform(platform);
        JSONObject config = parseConfig(model != null ? model.getConfig() : null);

        String family = firstNonBlank(
                model != null ? model.getModelFamily() : null,
                getString(config, "modelFamily", "family", "upstreamFamily", "upstream_family"));
        family = normalizeFamily(StrUtil.isNotBlank(family)
                ? family
                : inferFamily(normalizedPlatform, model != null ? model.getCode() : null,
                model != null ? model.getName() : null, model != null ? model.getModelType() : null));

        String protocol = firstNonBlank(
                model != null ? model.getModelProtocol() : null,
                getString(config, "modelProtocol", "videoProtocol", "protocol", "requestProtocol", "request_protocol"));
        protocol = normalizeProtocol(StrUtil.isNotBlank(protocol)
                ? protocol
                : inferProtocol(normalizedPlatform, family, model != null ? model.getModelType() : null));

        return new AiModelMetadata(platform, normalizedPlatform, family, protocol);
    }

    public RemoteModelMetadata resolveRemoteModel(String providerPlatform, String modelId, String ownedBy, Integer modelType) {
        String normalizedPlatform = normalizePlatform(providerPlatform);
        Integer inferredModelType = inferRemoteModelType(normalizedPlatform, modelId, ownedBy, modelType);
        String family = normalizeFamily(inferFamily(normalizedPlatform, modelId, ownedBy, inferredModelType));
        String protocol = normalizeProtocol(inferProtocol(normalizedPlatform, family, inferredModelType));
        String displayName = StrUtil.blankToDefault(ownedBy, modelId);
        boolean inferred = inferredModelType != null || StrUtil.isNotBlank(family) || StrUtil.isNotBlank(protocol);
        return new RemoteModelMetadata(normalizedPlatform, displayName, family, protocol, inferredModelType, inferred);
    }

    public String resolvePlatform(AiModel model) {
        if (model == null || model.getApiConfigId() == null) {
            return null;
        }
        ApiConfig apiConfig = apiConfigService.getById(model.getApiConfigId());
        return apiConfig != null ? apiConfig.getPlatform() : null;
    }

    public String normalizePlatform(String platform) {
        if (StrUtil.isBlank(platform)) {
            return "";
        }
        String normalized = platform.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "openai" -> "openai_compatible";
            case "vertexai" -> "vertex_ai";
            default -> normalized;
        };
    }

    public String normalizeFamily(String family) {
        if (StrUtil.isBlank(family)) {
            return null;
        }
        return family.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }

    public String normalizeProtocol(String protocol) {
        if (StrUtil.isBlank(protocol)) {
            return null;
        }
        return protocol.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }

    private Integer inferRemoteModelType(String platform, String modelId, String ownedBy, Integer currentType) {
        if (currentType != null) {
            return currentType;
        }
        String corpus = corpus(modelId, ownedBy);
        if (containsAny(corpus, "t2v", "i2v", "r2v", "video", "sora", "jimeng", "即梦", "kling", "可灵",
                "seedance", "veo", "pixverse", "hailuo", "wan2.7")) {
            return 3;
        }
        if (containsAny(corpus, "image", "img", "flux", "dall", "imagen", "qwen-image", "wan2.7-image")) {
            return 2;
        }
        return currentType;
    }

    private String inferFamily(String platform, String primaryText, String secondaryText, Integer modelType) {
        String corpus = corpus(primaryText, secondaryText);
        if (containsAny(corpus, "jimeng", "即梦")) {
            return "jimeng";
        }
        if (containsAny(corpus, "kling", "可灵")) {
            return "kling";
        }
        if (containsAny(corpus, "sora")) {
            return "sora";
        }
        if (containsAny(corpus, "agnes")) {
            return "agnes";
        }
        if (containsAny(corpus, "seedance", "豆包视频", "doubao-video")) {
            return "seedance";
        }
        if (containsAny(corpus, "wan", "通义万相")) {
            return modelType != null && modelType == 3 ? "wan_video" : "wan";
        }
        if (containsAny(corpus, "veo")) {
            return "veo";
        }
        if (containsAny(corpus, "pixverse")) {
            return "pixverse";
        }
        if (containsAny(corpus, "hailuo", "海螺")) {
            return "hailuo";
        }
        if (containsAny(corpus, "claude", "anthropic")) {
            return "claude";
        }
        if (containsAny(corpus, "gemini")) {
            return "gemini";
        }
        if (containsAny(corpus, "deepseek")) {
            return "deepseek";
        }
        if (containsAny(corpus, "qwen", "通义千问")) {
            return "qwen";
        }
        if (containsAny(corpus, "gpt", "o1", "o3", "o4", "openai")) {
            return "gpt";
        }
        return switch (platform) {
            case "anthropic" -> "claude";
            case "gemini", "vertex_ai" -> "gemini";
            case "dashscope" -> modelType != null && modelType == 3 ? "wan_video" : "qwen";
            case "volcengine" -> modelType != null && modelType == 3 ? "seedance" : "doubao";
            default -> "generic";
        };
    }

    private String inferProtocol(String platform, String family, Integer modelType) {
        if (modelType == null || modelType != 3) {
            return "generic";
        }
        return switch (platform) {
            case "googleflowreverseapi" -> "google_flow";
            case "dashscope" -> "wan";
            case "volcengine" -> "seedance";
            case "newapi" -> switch (StrUtil.blankToDefault(family, "generic")) {
                case "jimeng", "kling", "sora" -> family;
                default -> "generic";
            };
            default -> StrUtil.blankToDefault(family, "generic");
        };
    }

    private JSONObject parseConfig(String configJson) {
        if (StrUtil.isBlank(configJson)) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(configJson);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private String getString(JSONObject config, String... keys) {
        if (config == null) {
            return null;
        }
        for (String key : keys) {
            Object value = config.get(key);
            if (value == null) {
                continue;
            }
            String text = value.toString().trim();
            if (StrUtil.isNotBlank(text)) {
                return text;
            }
        }
        return null;
    }

    private String corpus(String primaryText, String secondaryText) {
        return (StrUtil.blankToDefault(primaryText, "") + " " + StrUtil.blankToDefault(secondaryText, ""))
                .toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... candidates) {
        if (StrUtil.isBlank(text)) {
            return false;
        }
        for (String candidate : candidates) {
            if (text.contains(candidate.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }
}