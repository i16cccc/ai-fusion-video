package com.stonewu.fusion.service.ai.agentscope;

import cn.hutool.json.JSONUtil;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * AgentScope 子 Agent 工具适配器。
 * <p>
 * 与 AgentScope 默认 SubAgentTool 不同，这里在 callAsync 中能拿到当前 ToolCallParam，
 * 因此可以把新创建的子 Agent 实例和父 tool_call_id 精确绑定，支持同名子 Agent 并行执行。
 */
@Slf4j
public class AgentScopeSubAgentToolAdapter implements AgentTool {

    private final String toolName;
    private final String description;
    private final Supplier<ReActAgent> agentFactory;
    private final StreamingEventHook streamingHook;
    private final AgentCancellationToken cancellationToken;

    public AgentScopeSubAgentToolAdapter(String toolName,
            String description,
            Supplier<ReActAgent> agentFactory,
            StreamingEventHook streamingHook,
            AgentCancellationToken cancellationToken) {
        this.toolName = toolName;
        this.description = description;
        this.agentFactory = agentFactory;
        this.streamingHook = streamingHook;
        this.cancellationToken = cancellationToken;
    }

    @Override
    public String getName() {
        return toolName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "message", Map.of(
                                "type", "string",
                                "description", "发送给子 Agent 的任务消息")),
                "required", List.of("message"),
                "additionalProperties", false);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.defer(() -> {
            cancellationToken.throwIfCancelled();

            ReActAgent subAgent = agentFactory.get();
            ToolUseBlock toolUseBlock = param != null ? param.getToolUseBlock() : null;
            String parentToolCallId = toolUseBlock != null ? toolUseBlock.getId() : null;
            streamingHook.bindSubAgentCall(subAgent, parentToolCallId);

            String inputMessage = buildInputMessage(param);
            log.info("[AgentScopeSubAgentToolAdapter] 子Agent工具被调用: name={}, parentToolCallId={}, input={}",
                    getName(), parentToolCallId, inputMessage);

            if (inputMessage == null || inputMessage.isBlank()) {
                return Mono.just(buildToolResult(param, JSONUtil.toJsonStr(Map.of(
                        "status", "error",
                        "message", "子 Agent 调用缺少 message 参数",
                        "toolName", getName()))));
            }

            Msg userMsg = Msg.builder()
                    .role(MsgRole.USER)
                    .textContent(inputMessage)
                    .build();

            return subAgent.call(userMsg)
                    .map(finalMsg -> {
                        cancellationToken.throwIfCancelled();
                        String result = finalMsg != null ? finalMsg.getTextContent() : "";
                        return buildToolResult(param, result);
                    });
        }).onErrorResume(AgentCancelledException.class, e -> {
            log.info("[AgentScopeSubAgentToolAdapter] 子Agent工具执行被取消: name={}", getName());
            return Mono.error(e);
        }).onErrorResume(e -> {
            if (e instanceof AgentCancelledException) {
                return Mono.error(e);
            }
            log.error("[AgentScopeSubAgentToolAdapter] 子Agent工具执行失败: name={}", getName(), e);
            String errorResult = JSONUtil.toJsonStr(Map.of(
                    "status", "error",
                    "message", "子 Agent 执行失败: " + e.getMessage(),
                    "toolName", getName()));
            return Mono.just(buildToolResult(param, errorResult));
        });
    }

    private String buildInputMessage(ToolCallParam param) {
        if (param == null || param.getInput() == null || param.getInput().isEmpty()) {
            return "";
        }
        Object message = param.getInput().get("message");
        if (message != null) {
            String messageText = String.valueOf(message);
            if (!messageText.isBlank()) {
                return messageText;
            }
        }
        return JSONUtil.toJsonStr(param.getInput());
    }

    private ToolResultBlock buildToolResult(ToolCallParam param, String result) {
        ToolResultBlock block = ToolResultBlock.text(result != null ? result : "");
        ToolUseBlock toolUseBlock = param != null ? param.getToolUseBlock() : null;
        if (toolUseBlock == null) {
            return block;
        }
        return block.withIdAndName(toolUseBlock.getId(), toolUseBlock.getName());
    }
}