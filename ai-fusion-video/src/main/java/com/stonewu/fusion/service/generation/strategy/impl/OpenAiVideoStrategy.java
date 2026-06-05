package com.stonewu.fusion.service.generation.strategy.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.entity.generation.VideoItem;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.entity.storage.StorageConfig;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.ai.ApiConfigService;
import com.stonewu.fusion.service.ai.ModelPresetService;
import com.stonewu.fusion.service.ai.proxy.AiProxySupport;
import com.stonewu.fusion.service.generation.VideoGenerationService;
import com.stonewu.fusion.service.generation.strategy.VideoGenerationStrategy;
import com.stonewu.fusion.service.storage.MediaStorageService;
import com.stonewu.fusion.service.storage.StorageConfigService;
import com.stonewu.fusion.service.system.PresetArtStyleResourceResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容视频生成策略（Sora Videos API）。
 * <p>
 * 对接官方接口：
 * POST /v1/videos                 创建视频生成任务（multipart/form-data）
 * GET  /v1/videos/{id}            查询任务状态
 * GET  /v1/videos/{id}/content    下载生成的视频内容
 * <p>
 * 支持文生视频与图生视频（input_reference 参考图）。视频内容需带 Authorization 鉴权下载，
 * 因此在本策略内主动拉取字节并落地到持久化存储，VideoItem.videoUrl 直接保存为本地可访问地址。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpenAiVideoStrategy implements VideoGenerationStrategy {

    public static final String PLATFORM = "openai_compatible";

    private static final String DEFAULT_BASE_URL = "https://api.openai.com";
    private static final String DEFAULT_VIDEO_MODEL = "sora-2";
    private static final String DEFAULT_LOCAL_MEDIA_BASE_PATH = "./data/media";
    private static final long DEFAULT_POLL_INTERVAL_MILLIS = 10000L;
    private static final long DEFAULT_POLL_TIMEOUT_MILLIS = 60L * 60L * 1000L;
    private static final int RESPONSE_PREVIEW_LENGTH = 240;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AiModelService aiModelService;
    private final ApiConfigService apiConfigService;
    private final VideoGenerationService videoGenerationService;
    private final ModelPresetService modelPresetService;
    private final MediaStorageService mediaStorageService;
    private final StorageConfigService storageConfigService;
    private final PresetArtStyleResourceResolver presetArtStyleResourceResolver;

    private final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    @Override
    public String getName() {
        return PLATFORM;
    }

    @Override
    public String submit(VideoTask task) {
        AiModel model = resolveModel(task);
        ApiConfig apiConfig = resolveApiConfig(model);
        String modelCode = resolveModelCode(model);
        JSONObject modelConfig = resolveModelConfig(modelCode, model);

        List<VideoItem> items = videoGenerationService.listItems(task.getId());
        if (items.isEmpty()) {
            throw new BusinessException("视频任务缺少生成条目");
        }

        String firstPlatformTaskId = null;
        for (VideoItem item : items) {
            if (StrUtil.isNotBlank(item.getPlatformTaskId())) {
                firstPlatformTaskId = StrUtil.blankToDefault(firstPlatformTaskId, item.getPlatformTaskId());
                continue;
            }

            RequestBody requestBody = buildSubmitBody(task, modelCode, apiConfig, modelConfig);
            String platformTaskId = submitTask(apiConfig, requestBody);
            item.setPlatformTaskId(platformTaskId);
            videoGenerationService.updateItem(item);

            if (firstPlatformTaskId == null) {
                firstPlatformTaskId = platformTaskId;
            }
        }

        log.info("[OpenAI Video] 任务已创建: taskId={}, model={}, count={}",
                task.getTaskId(), modelCode, items.size());
        return firstPlatformTaskId;
    }

    @Override
    public void poll(String platformTaskId, VideoTask task) {
        AiModel model = resolveModel(task);
        ApiConfig apiConfig = resolveApiConfig(model);
        JSONObject modelConfig = resolveModelConfig(resolveModelCode(model), model);

        List<VideoItem> items = videoGenerationService.listItems(task.getId());
        if (items.isEmpty()) {
            OpenAiVideoResult result = waitForTask(apiConfig, platformTaskId, modelConfig);
            persistResult(result, apiConfig, null, task);
            task.setSuccessCount(1);
            videoGenerationService.update(task);
            return;
        }

        int successCount = 0;
        for (VideoItem item : items) {
            String currentPlatformTaskId = StrUtil.blankToDefault(item.getPlatformTaskId(), platformTaskId);
            if (StrUtil.isBlank(currentPlatformTaskId)) {
                item.setStatus(2);
                item.setErrorMsg("OpenAI 平台任务 ID 为空");
                videoGenerationService.updateItem(item);
                throw new BusinessException("OpenAI 平台任务 ID 为空");
            }

            try {
                OpenAiVideoResult result = waitForTask(apiConfig, currentPlatformTaskId, modelConfig);
                persistResult(result, apiConfig, item, task);
                item.setPlatformTaskId(currentPlatformTaskId);
                item.setStatus(1);
                item.setErrorMsg(null);
                videoGenerationService.updateItem(item);
                successCount++;
            } catch (BusinessException e) {
                item.setStatus(2);
                item.setErrorMsg(e.getMessage());
                videoGenerationService.updateItem(item);
                throw e;
            }
        }

        task.setSuccessCount(successCount);
        videoGenerationService.update(task);
        log.info("[OpenAI Video] 视频生成完成: taskId={}, successCount={}", task.getTaskId(), successCount);
    }

    private void persistResult(OpenAiVideoResult result, ApiConfig apiConfig, VideoItem item, VideoTask task) {
        String videoUrl = downloadVideoContent(apiConfig, result.id());
        if (StrUtil.isBlank(videoUrl)) {
            throw new BusinessException("OpenAI 视频任务成功但未获取到视频内容: " + result.id());
        }
        String coverUrl = downloadThumbnail(apiConfig, result.id());
        if (item != null) {
            item.setVideoUrl(videoUrl);
            item.setCoverUrl(coverUrl);
            item.setDuration(result.seconds() != null ? result.seconds() : task.getDuration());
        }
    }

    private RequestBody buildSubmitBody(VideoTask task, String modelCode, ApiConfig apiConfig, JSONObject modelConfig) {
        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", modelCode);

        if (StrUtil.isNotBlank(task.getPrompt())) {
            builder.addFormDataPart("prompt", task.getPrompt());
        }

        String seconds = resolveSeconds(task, modelConfig);
        if (StrUtil.isNotBlank(seconds)) {
            builder.addFormDataPart("seconds", seconds);
        }

        String size = resolveSize(task, modelConfig);
        if (StrUtil.isNotBlank(size)) {
            builder.addFormDataPart("size", size);
        }

        String referenceImageUrl = resolveReferenceImageUrl(task);
        if (StrUtil.isNotBlank(referenceImageUrl)) {
            BinaryResource resource;
            try {
                resource = loadBinaryResource(referenceImageUrl, apiConfig);
            } catch (IOException e) {
                throw new BusinessException("加载 OpenAI 视频参考图失败: " + e.getMessage());
            }
            builder.addFormDataPart(
                    "input_reference",
                    "reference." + resource.extension(),
                    RequestBody.create(resource.bytes(), mediaTypeOrDefault(resource.mimeType()))
            );
        }

        return builder.build();
    }

    private String submitTask(ApiConfig apiConfig, RequestBody requestBody) {
        Request request = new Request.Builder()
                .url(resolveVideosUrl(apiConfig))
                .addHeader("Authorization", "Bearer " + apiConfig.getApiKey())
                .post(requestBody)
                .build();

        OkHttpClient client = AiProxySupport.okHttpClient(okHttpClient, apiConfig);
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new BusinessException("OpenAI 视频任务提交失败: HTTP " + response.code() + " "
                        + extractErrorMessage(responseBody));
            }
            OpenAiVideoResult result = parseVideoResult(responseBody);
            if (StrUtil.isBlank(result.id())) {
                throw new BusinessException("OpenAI 视频任务未返回 id");
            }
            return result.id();
        } catch (IOException e) {
            throw new BusinessException("OpenAI 视频任务提交异常: " + e.getMessage());
        }
    }

    private OpenAiVideoResult waitForTask(ApiConfig apiConfig, String platformTaskId, JSONObject modelConfig) {
        long pollIntervalMillis = resolvePollIntervalMillis(modelConfig);
        long deadline = System.currentTimeMillis() + resolvePollTimeoutMillis(modelConfig);

        while (System.currentTimeMillis() <= deadline) {
            OpenAiVideoResult result = queryTask(apiConfig, platformTaskId);
            String status = normalizeStatus(result.status());

            if ("completed".equals(status) || "succeeded".equals(status) || "success".equals(status)) {
                return result;
            }
            if ("failed".equals(status) || "error".equals(status)
                    || "canceled".equals(status) || "cancelled".equals(status)) {
                throw new BusinessException("OpenAI 视频任务失败: "
                        + StrUtil.blankToDefault(result.errorMessage(), "未知错误"));
            }

            sleepQuietly(pollIntervalMillis);
        }

        throw new BusinessException("OpenAI 视频任务轮询超时: " + platformTaskId);
    }

    private OpenAiVideoResult queryTask(ApiConfig apiConfig, String platformTaskId) {
        Request request = new Request.Builder()
                .url(resolveVideosUrl(apiConfig) + "/" + platformTaskId)
                .addHeader("Authorization", "Bearer " + apiConfig.getApiKey())
                .get()
                .build();

        OkHttpClient client = AiProxySupport.okHttpClient(okHttpClient, apiConfig);
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new BusinessException("OpenAI 视频任务查询失败: HTTP " + response.code() + " "
                        + extractErrorMessage(responseBody));
            }
            return parseVideoResult(responseBody);
        } catch (IOException e) {
            throw new BusinessException("OpenAI 视频任务查询异常: " + e.getMessage());
        }
    }

    private String downloadVideoContent(ApiConfig apiConfig, String platformTaskId) {
        Request request = new Request.Builder()
                .url(resolveVideosUrl(apiConfig) + "/" + platformTaskId + "/content")
                .addHeader("Authorization", "Bearer " + apiConfig.getApiKey())
                .addHeader("Accept", "video/*,*/*;q=0.8")
                .get()
                .build();

        OkHttpClient client = AiProxySupport.okHttpClient(okHttpClient, apiConfig);
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                String body = response.body() != null ? response.body().string() : "";
                throw new BusinessException("OpenAI 视频内容下载失败: HTTP " + response.code() + " "
                        + extractErrorMessage(body));
            }
            byte[] bytes = response.body().bytes();
            String extension = extensionFromVideoMimeType(response.header("Content-Type"));
            return mediaStorageService.storeBytes(bytes, "videos", extension);
        } catch (IOException e) {
            throw new BusinessException("OpenAI 视频内容下载异常: " + e.getMessage());
        }
    }

    private String downloadThumbnail(ApiConfig apiConfig, String platformTaskId) {
        Request request = new Request.Builder()
                .url(resolveVideosUrl(apiConfig) + "/" + platformTaskId + "/content?variant=thumbnail")
                .addHeader("Authorization", "Bearer " + apiConfig.getApiKey())
                .addHeader("Accept", "image/*,*/*;q=0.8")
                .get()
                .build();

        OkHttpClient client = AiProxySupport.okHttpClient(okHttpClient, apiConfig);
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            byte[] bytes = response.body().bytes();
            if (bytes.length == 0) {
                return null;
            }
            String extension = extensionFromImageMimeType(response.header("Content-Type"));
            return mediaStorageService.storeBytes(bytes, "images", extension);
        } catch (Exception e) {
            log.warn("[OpenAI Video] 视频封面下载失败（忽略）: taskId={}, error={}", platformTaskId, e.getMessage());
            return null;
        }
    }

    private OpenAiVideoResult parseVideoResult(String responseBody) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(responseBody);
            JsonNode node = root;
            JsonNode data = root.path("data");
            if (data.isArray() && !data.isEmpty()) {
                node = data.get(0);
            } else if (data.isObject()) {
                node = data;
            }

            String id = firstText(node, "id", "video_id", "task_id");
            String status = firstText(node, "status", "state");
            Integer seconds = parsePositiveInt(firstText(node, "seconds", "duration"));
            String errorMessage = extractErrorMessage(root);
            return new OpenAiVideoResult(id, status, seconds, errorMessage);
        } catch (IOException e) {
            throw new BusinessException("OpenAI 视频响应不是合法 JSON: " + previewResponse(responseBody));
        }
    }

    private String resolveSeconds(VideoTask task, JSONObject modelConfig) {
        if (task.getDuration() != null && task.getDuration() > 0) {
            return String.valueOf(task.getDuration());
        }
        Integer defaultDuration = getInteger(modelConfig, "defaultDuration", "seconds", "duration");
        return defaultDuration != null && defaultDuration > 0 ? String.valueOf(defaultDuration) : null;
    }

    private String resolveSize(VideoTask task, JSONObject modelConfig) {
        String resolution = normalizeSize(task.getResolution());
        if (StrUtil.isNotBlank(resolution)) {
            return resolution;
        }
        return normalizeSize(getString(modelConfig, "size", "defaultResolution", "resolution"));
    }

    private String normalizeSize(String size) {
        if (StrUtil.isBlank(size)) {
            return null;
        }
        String normalized = size.trim().toLowerCase(Locale.ROOT).replace('*', 'x').replace('×', 'x');
        return normalized.matches("\\d+x\\d+") ? normalized : null;
    }

    private String resolveReferenceImageUrl(VideoTask task) {
        if (StrUtil.isNotBlank(task.getFirstFrameImageUrl())) {
            return task.getFirstFrameImageUrl();
        }
        return firstUrl(task.getReferenceImageUrls());
    }

    private String firstUrl(String referenceImageUrls) {
        if (StrUtil.isBlank(referenceImageUrls)) {
            return null;
        }
        try {
            List<String> urls = JSONUtil.toList(JSONUtil.parseArray(referenceImageUrls), String.class);
            for (String url : urls) {
                if (StrUtil.isNotBlank(url)) {
                    return url.trim();
                }
            }
        } catch (Exception ignored) {
            String trimmed = referenceImageUrls.trim();
            return trimmed.startsWith("[") ? null : trimmed;
        }
        return null;
    }

    private String resolveVideosUrl(ApiConfig apiConfig) {
        String baseUrl = normalizeBaseUrl(StrUtil.blankToDefault(apiConfig != null ? apiConfig.getApiUrl() : null,
                DEFAULT_BASE_URL));
        if (endsWithIgnoreCase(baseUrl, "/videos")) {
            return baseUrl;
        }
        if (endsWithIgnoreCase(baseUrl, "/v1")) {
            return baseUrl + "/videos";
        }
        boolean appendV1 = shouldAutoAppendV1Path(apiConfig);
        return baseUrl + (appendV1 ? "/v1/videos" : "/videos");
    }

    private boolean shouldAutoAppendV1Path(ApiConfig apiConfig) {
        if (apiConfig == null) {
            return true;
        }
        if (!PLATFORM.equalsIgnoreCase(apiConfig.getPlatform())) {
            return true;
        }
        return !Boolean.FALSE.equals(apiConfig.getAutoAppendV1Path());
    }

    private AiModel resolveModel(VideoTask task) {
        if (task.getModelId() == null) {
            throw new BusinessException("OpenAI 视频任务缺少 modelId");
        }
        AiModel model = aiModelService.getById(task.getModelId());
        if (model == null) {
            throw new BusinessException("OpenAI 视频模型不存在: modelId=" + task.getModelId());
        }
        return model;
    }

    private ApiConfig resolveApiConfig(AiModel model) {
        if (model.getApiConfigId() == null) {
            throw new BusinessException("OpenAI 视频模型缺少 apiConfigId");
        }
        ApiConfig apiConfig = apiConfigService.getById(model.getApiConfigId());
        if (apiConfig == null) {
            throw new BusinessException("OpenAI API 配置不存在");
        }
        if (StrUtil.isBlank(apiConfig.getApiKey())) {
            throw new BusinessException("OpenAI 缺少 API Key 配置");
        }
        return apiConfig;
    }

    private String resolveModelCode(AiModel model) {
        return model != null && StrUtil.isNotBlank(model.getCode()) ? model.getCode() : DEFAULT_VIDEO_MODEL;
    }

    private JSONObject resolveModelConfig(String modelCode, AiModel model) {
        JSONObject merged = new JSONObject();
        String actualModelCode = StrUtil.blankToDefault(modelCode, model != null ? model.getCode() : null);
        if (modelPresetService != null && StrUtil.isNotBlank(actualModelCode)) {
            mergeConfig(merged, parseConfig(modelPresetService.getPresetConfig(actualModelCode)));
        }
        if (model != null) {
            mergeConfig(merged, parseConfig(model.getConfig()));
        }
        return merged;
    }

    private JSONObject parseConfig(String configJson) {
        if (StrUtil.isBlank(configJson)) {
            return null;
        }
        try {
            return JSONUtil.parseObj(configJson);
        } catch (Exception e) {
            log.warn("[OpenAI Video] 模型配置解析失败，已忽略附加参数: config={}", configJson);
            return null;
        }
    }

    private void mergeConfig(JSONObject target, JSONObject source) {
        if (target == null || source == null || source.isEmpty()) {
            return;
        }
        for (String key : source.keySet()) {
            target.set(key, source.get(key));
        }
    }

    private long resolvePollIntervalMillis(JSONObject modelConfig) {
        Long interval = getLong(modelConfig, "pollIntervalMillis", "pollIntervalMs", "pollInterval");
        return interval != null && interval > 0 ? interval : DEFAULT_POLL_INTERVAL_MILLIS;
    }

    private long resolvePollTimeoutMillis(JSONObject modelConfig) {
        Long timeoutMillis = getLong(modelConfig, "pollTimeoutMillis", "pollTimeoutMs");
        if (timeoutMillis != null && timeoutMillis > 0) {
            return timeoutMillis;
        }
        Integer timeoutSeconds = getInteger(modelConfig, "pollTimeoutSeconds", "pollTimeout", "timeoutSeconds");
        if (timeoutSeconds != null && timeoutSeconds > 0) {
            return timeoutSeconds * 1000L;
        }
        return DEFAULT_POLL_TIMEOUT_MILLIS;
    }

    private BinaryResource loadBinaryResource(String sourceUrl, ApiConfig apiConfig) throws IOException {
        if (StrUtil.isBlank(sourceUrl)) {
            throw new BusinessException("OpenAI 视频参考图地址为空");
        }
        String trimmed = sourceUrl.trim();
        if (trimmed.startsWith("data:")) {
            return parseDataUrl(trimmed);
        }
        if (trimmed.startsWith("/media/")) {
            return loadLocalMedia(trimmed);
        }
        if (presetArtStyleResourceResolver.isPresetArtStylePath(trimmed)) {
            PresetArtStyleResourceResolver.PresetArtStyleResource resource = presetArtStyleResourceResolver.load(trimmed);
            return new BinaryResource(resource.bytes(), resource.mimeType(), extensionFromImageMimeType(resource.mimeType()));
        }
        if (trimmed.startsWith("file:")) {
            return loadFile(Paths.get(URI.create(trimmed)));
        }
        if (StrUtil.startWithIgnoreCase(trimmed, "http://") || StrUtil.startWithIgnoreCase(trimmed, "https://")) {
            Request request = new Request.Builder()
                    .url(trimmed)
                    .get()
                    .addHeader("Accept", "image/*,*/*;q=0.8")
                    .build();
            OkHttpClient client = AiProxySupport.okHttpClient(okHttpClient, apiConfig);
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new BusinessException("下载视频参考图失败: HTTP " + response.code() + " url=" + trimmed);
                }
                String mimeType = normalizeImageMimeType(response.header("Content-Type"), trimmed);
                return new BinaryResource(response.body().bytes(), mimeType, extensionFromImageMimeType(mimeType));
            }
        }

        Path localPath = Paths.get(trimmed);
        if (Files.exists(localPath) && Files.isRegularFile(localPath)) {
            return loadFile(localPath);
        }
        throw new BusinessException("视频参考图地址不可访问: " + trimmed);
    }

    private BinaryResource parseDataUrl(String sourceUrl) {
        int commaIndex = sourceUrl.indexOf(',');
        if (commaIndex <= 0) {
            throw new BusinessException("OpenAI 视频参考图 data URL 格式非法");
        }
        String metadata = sourceUrl.substring(0, commaIndex);
        String payload = sourceUrl.substring(commaIndex + 1);
        String mimeType = normalizeImageMimeType(metadata.substring("data:".length()), sourceUrl);
        try {
            byte[] bytes = Base64.getDecoder().decode(payload.getBytes(StandardCharsets.UTF_8));
            return new BinaryResource(bytes, mimeType, extensionFromImageMimeType(mimeType));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("OpenAI 视频参考图 data URL base64 非法: " + e.getMessage());
        }
    }

    private BinaryResource loadLocalMedia(String sourceUrl) throws IOException {
        String relativePath = sourceUrl.replaceFirst("^/media/?", "");
        List<Path> candidates = new ArrayList<>();
        StorageConfig config = storageConfigService.getDefaultConfig();
        if (config != null && StrUtil.isNotBlank(config.getBasePath())) {
            candidates.add(Paths.get(config.getBasePath()).resolve(relativePath));
        }
        candidates.add(Paths.get(DEFAULT_LOCAL_MEDIA_BASE_PATH).resolve(relativePath));

        for (Path candidate : candidates) {
            if (candidate != null && Files.exists(candidate) && Files.isRegularFile(candidate)) {
                return loadFile(candidate);
            }
        }
        throw new BusinessException("本地视频参考图不存在: " + sourceUrl);
    }

    private BinaryResource loadFile(Path path) throws IOException {
        String mimeType = normalizeImageMimeType(null, path.getFileName().toString());
        return new BinaryResource(Files.readAllBytes(path), mimeType, extensionFromImageMimeType(mimeType));
    }

    private String normalizeImageMimeType(String contentType, String sourceUrl) {
        if (StrUtil.isNotBlank(contentType)) {
            return contentType.split(";", 2)[0].trim();
        }
        String lower = sourceUrl == null ? "" : sourceUrl.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/png";
    }

    private String extensionFromImageMimeType(String mimeType) {
        String normalized = normalizeImageMimeType(mimeType, mimeType).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "png";
        };
    }

    private String extensionFromVideoMimeType(String contentType) {
        if (StrUtil.isBlank(contentType)) {
            return "mp4";
        }
        String normalized = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "video/webm" -> "webm";
            case "video/quicktime" -> "mov";
            case "video/x-matroska" -> "mkv";
            default -> "mp4";
        };
    }

    private MediaType mediaTypeOrDefault(String mimeType) {
        try {
            return MediaType.get(StrUtil.blankToDefault(mimeType, "image/png"));
        } catch (Exception ignored) {
            return MediaType.get("application/octet-stream");
        }
    }

    private String extractErrorMessage(String responseBody) {
        if (StrUtil.isBlank(responseBody)) {
            return "响应体为空";
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(responseBody);
            String message = extractErrorMessage(root);
            return StrUtil.isNotBlank(message) ? message : previewResponse(responseBody);
        } catch (Exception ignored) {
            return previewResponse(responseBody);
        }
    }

    private String extractErrorMessage(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode error = root.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            String message = firstText(error, "message", "detail", "code");
            if (StrUtil.isNotBlank(message)) {
                return message;
            }
            if (error.isTextual()) {
                return error.asText();
            }
        }
        return firstText(root, "message", "detail");
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText();
                if (StrUtil.isNotBlank(text)) {
                    return text.trim();
                }
            }
        }
        return null;
    }

    private Integer parsePositiveInt(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String getString(JSONObject config, String... keys) {
        if (config == null) {
            return null;
        }
        for (String key : keys) {
            String value = config.getStr(key);
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private Integer getInteger(JSONObject config, String... keys) {
        if (config == null) {
            return null;
        }
        for (String key : keys) {
            if (!config.containsKey(key)) {
                continue;
            }
            Object value = config.get(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value != null) {
                try {
                    return Integer.parseInt(value.toString().trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private Long getLong(JSONObject config, String... keys) {
        if (config == null) {
            return null;
        }
        for (String key : keys) {
            if (!config.containsKey(key)) {
                continue;
            }
            Object value = config.get(key);
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value != null) {
                try {
                    return Long.parseLong(value.toString().trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private String normalizeBaseUrl(String baseUrl) {
        return StrUtil.blankToDefault(baseUrl, DEFAULT_BASE_URL).trim().replaceAll("/+$", "");
    }

    private boolean endsWithIgnoreCase(String text, String suffix) {
        return text != null && suffix != null && text.toLowerCase(Locale.ROOT)
                .endsWith(suffix.toLowerCase(Locale.ROOT));
    }

    private String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
    }

    private void sleepQuietly(long intervalMillis) {
        try {
            TimeUnit.MILLISECONDS.sleep(intervalMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("OpenAI 视频任务轮询被中断");
        }
    }

    private String previewResponse(String responseBody) {
        if (StrUtil.isBlank(responseBody)) {
            return "<empty>";
        }
        String normalized = responseBody.replaceAll("\\s+", " ").trim();
        return normalized.length() <= RESPONSE_PREVIEW_LENGTH
                ? normalized
                : normalized.substring(0, RESPONSE_PREVIEW_LENGTH) + "...";
    }

    private record BinaryResource(byte[] bytes, String mimeType, String extension) {
    }

    private record OpenAiVideoResult(String id, String status, Integer seconds, String errorMessage) {
    }
}
