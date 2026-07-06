/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.agui.model.AguiFunctionCall;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.AguiToolCall;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converter between AG-UI messages and AgentScope messages.
 *
 * <p>This class handles the bidirectional conversion between the AG-UI protocol's
 * message format and AgentScope's internal message format.
 */
public class AguiMessageConverter {
    /**
     * Creates a new AguiMessageConverter
     */
    public AguiMessageConverter() {}

    /**
     * Convert an AG-UI message to an AgentScope message.
     *
     * @param aguiMessage The AG-UI message to convert
     * @return The converted AgentScope message
     */
    public Msg toMsg(AguiMessage aguiMessage) {
        MsgRole role = convertRole(aguiMessage.getRole());
        List<ContentBlock> blocks = new ArrayList<>();

        // Add content if present
        Object content = aguiMessage.getContent();
        if (content != null && !isContentEmpty(content)) {
            if (aguiMessage.isToolMessage() && aguiMessage.getToolCallId() != null) {
                // For tool messages, wrap string content in ToolResultBlock
                String text = content instanceof String ? (String) content : content.toString();
                blocks.add(
                        ToolResultBlock.of(
                                aguiMessage.getToolCallId(),
                                null,
                                TextBlock.builder().text(text).build()));
            } else if (content instanceof String text) {
                blocks.add(TextBlock.builder().text(text).build());
            } else if (content instanceof List<?> contentList) {
                // Multimodal user message content (InputContent[])
                blocks.addAll(convertInputContentList(contentList));
            }
            // Activity messages carry structured frontend payloads and are not forwarded
            // to the agent; their content is intentionally skipped here.
        }

        // Add tool calls if present (for assistant messages)
        if (aguiMessage.hasToolCalls()) {
            for (AguiToolCall tc : aguiMessage.getToolCalls()) {
                blocks.add(toToolUseBlock(tc));
            }
        }

        return Msg.builder().id(aguiMessage.getId()).role(role).content(blocks).build();
    }

    /**
     * Convert an AgentScope message to an AG-UI message.
     *
     * <p>If the message contains multimodal blocks ({@link ImageBlock}, {@link AudioBlock},
     * {@link VideoBlock}, {@link DataBlock}), the content is converted to an {@code InputContent[]}
     * list as defined by the AG-UI specification. Otherwise, the content is a plain string.
     *
     * @param msg The AgentScope message to convert
     * @return The converted AG-UI message
     */
    public AguiMessage toAguiMessage(Msg msg) {
        String role = convertRole(msg.getRole());
        List<AguiToolCall> toolCalls = new ArrayList<>();
        String toolCallId = null;

        // Separate text content from multimodal blocks
        StringBuilder textContent = new StringBuilder();
        List<ContentBlock> multimodalBlocks = new ArrayList<>();

        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock tb) {
                if (textContent.length() > 0) {
                    textContent.append("\n");
                }
                textContent.append(tb.getText());
            } else if (block instanceof ToolUseBlock tub) {
                toolCalls.add(toAguiToolCall(tub));
            } else if (block instanceof ToolResultBlock trb) {
                toolCallId = trb.getId();
                // Extract text content from tool result
                for (ContentBlock output : trb.getOutput()) {
                    if (output instanceof TextBlock tb) {
                        if (textContent.length() > 0) {
                            textContent.append("\n");
                        }
                        textContent.append(tb.getText());
                    }
                }
            } else if (block instanceof ImageBlock
                    || block instanceof AudioBlock
                    || block instanceof VideoBlock
                    || block instanceof DataBlock) {
                multimodalBlocks.add(block);
            }
        }

        // Build content: if multimodal blocks exist, use InputContent[] format;
        // otherwise use plain string.
        Object content;
        if (!multimodalBlocks.isEmpty()) {
            List<Map<String, Object>> inputContentList = new ArrayList<>();
            // Include text as a TextInputContent entry
            if (textContent.length() > 0) {
                inputContentList.add(Map.of("type", "text", "text", textContent.toString()));
            }
            // Convert each multimodal block to InputContent format
            for (ContentBlock block : multimodalBlocks) {
                Map<String, Object> entry = toInputContent(block);
                if (entry != null) {
                    inputContentList.add(entry);
                }
            }
            content = inputContentList;
        } else {
            content = textContent.length() > 0 ? textContent.toString() : null;
        }

        return new AguiMessage(
                msg.getId(), role, content, toolCalls.isEmpty() ? null : toolCalls, toolCallId);
    }

    /**
     * Convert a list of AG-UI messages to AgentScope messages.
     *
     * @param aguiMessages The AG-UI messages to convert
     * @return The converted AgentScope messages
     */
    public List<Msg> toMsgList(List<AguiMessage> aguiMessages) {
        return aguiMessages.stream().map(this::toMsg).collect(Collectors.toList());
    }

    /**
     * Convert a list of AgentScope messages to AG-UI messages.
     *
     * @param msgs The AgentScope messages to convert
     * @return The converted AG-UI messages
     */
    public List<AguiMessage> toAguiMessageList(List<Msg> msgs) {
        return msgs.stream().map(this::toAguiMessage).collect(Collectors.toList());
    }

    /**
     * Convert an AG-UI role string to an AgentScope MsgRole.
     *
     * @param role The AG-UI role string
     * @return The corresponding MsgRole
     */
    private MsgRole convertRole(String role) {
        return switch (role.toLowerCase()) {
            case "user" -> MsgRole.USER;
            case "assistant" -> MsgRole.ASSISTANT;
            case "system", "developer" -> MsgRole.SYSTEM;
            case "tool" -> MsgRole.TOOL;
            // "activity" and "reasoning" have no direct AgentScope equivalent;
            // fall back to user so the message is still delivered.
            default -> MsgRole.USER;
        };
    }

    /**
     * Convert an AgentScope MsgRole to an AG-UI role string.
     *
     * @param role The AgentScope MsgRole
     * @return The corresponding role string
     */
    private String convertRole(MsgRole role) {
        return switch (role) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
            case TOOL -> "tool";
        };
    }

    /**
     * Convert an AG-UI tool call to an AgentScope ToolUseBlock.
     *
     * @param tc The AG-UI tool call
     * @return The converted ToolUseBlock
     */
    private ToolUseBlock toToolUseBlock(AguiToolCall tc) {
        Map<String, Object> input = parseJsonArguments(tc.getFunction().getArguments());
        return ToolUseBlock.builder()
                .id(tc.getId())
                .name(tc.getFunction().getName())
                .input(input)
                .build();
    }

    /**
     * Convert an AgentScope ToolUseBlock to an AG-UI tool call.
     *
     * @param tub The AgentScope ToolUseBlock
     * @return The converted AG-UI tool call
     */
    private AguiToolCall toAguiToolCall(ToolUseBlock tub) {
        String arguments = serializeArguments(tub.getInput());
        AguiFunctionCall function = new AguiFunctionCall(tub.getName(), arguments);
        return new AguiToolCall(tub.getId(), function);
    }

    /**
     * Parse JSON arguments string to a Map.
     *
     * @param arguments The JSON arguments string
     * @return The parsed Map
     */
    private Map<String, Object> parseJsonArguments(String arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        try {
            return JsonUtils.getJsonCodec()
                    .fromJson(arguments, new TypeReference<Map<String, Object>>() {});
        } catch (JsonException e) {
            return Map.of();
        }
    }

    /**
     * Serialize arguments Map to JSON string.
     *
     * @param arguments The arguments Map
     * @return The JSON string
     */
    private String serializeArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        try {
            return JsonUtils.getJsonCodec().toJson(arguments);
        } catch (JsonException e) {
            return "{}";
        }
    }

    /**
     * Check whether the given content object is considered empty.
     *
     * @param content The content object
     * @return true if the content is empty
     */
    private boolean isContentEmpty(Object content) {
        if (content instanceof String s) {
            return s.isEmpty();
        }
        if (content instanceof List<?> list) {
            return list.isEmpty();
        }
        if (content instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        return false;
    }

    /**
     * Convert a list of AG-UI InputContent items to AgentScope content blocks.
     *
     * @param contentList The multimodal content list
     * @return The converted content blocks
     */
    private List<ContentBlock> convertInputContentList(List<?> contentList) {
        List<ContentBlock> blocks = new ArrayList<>();
        for (Object item : contentList) {
            if (item instanceof Map<?, ?> map) {
                String type = (String) map.get("type");
                if (type == null) {
                    continue;
                }
                Source source =
                        map.containsKey("source") ? toSource((Map<?, ?>) map.get("source")) : null;
                ContentBlock block =
                        switch (type) {
                            case "text" ->
                                    TextBlock.builder().text((String) map.get("text")).build();
                            case "image" ->
                                    source != null
                                            ? ImageBlock.builder().source(source).build()
                                            : null;
                            case "audio" ->
                                    source != null
                                            ? AudioBlock.builder().source(source).build()
                                            : null;
                            case "video" ->
                                    source != null
                                            ? VideoBlock.builder().source(source).build()
                                            : null;
                            case "document" ->
                                    source != null
                                            ? DataBlock.builder().source(source).build()
                                            : null;
                            default -> null;
                        };
                if (block != null) {
                    blocks.add(block);
                }
            }
        }
        return blocks;
    }

    /**
     * Convert an AgentScope multimodal ContentBlock to an AG-UI InputContent map.
     *
     * @param block The multimodal content block
     * @return The InputContent map, or null if the block type is not supported
     */
    private Map<String, Object> toInputContent(ContentBlock block) {
        if (block instanceof ImageBlock ib) {
            return Map.of("type", "image", "source", fromSource(ib.getSource()));
        }
        if (block instanceof AudioBlock ab) {
            return Map.of("type", "audio", "source", fromSource(ab.getSource()));
        }
        if (block instanceof VideoBlock vb) {
            return Map.of("type", "video", "source", fromSource(vb.getSource()));
        }
        if (block instanceof DataBlock db) {
            return Map.of("type", "document", "source", fromSource(db.getSource()));
        }
        return null;
    }

    /**
     * Convert an AgentScope {@link Source} to an AG-UI InputContentSource map.
     *
     * @param source The AgentScope source
     * @return The InputContentSource map
     */
    private Map<String, Object> fromSource(Source source) {
        if (source instanceof Base64Source b64) {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("type", "data");
            map.put("value", b64.getData());
            map.put("mimeType", b64.getMediaType());
            return map;
        }
        if (source instanceof URLSource urlSource) {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("type", "url");
            map.put("value", urlSource.getUrl());
            return map;
        }
        return Map.of();
    }

    /**
     * Convert an AG-UI InputContentSource map to an AgentScope Source.
     *
     * <p>Supports both {@code data} (base64) and {@code url} sources.
     *
     * @param sourceMap The source map
     * @return The converted source, or null if unsupported
     */
    private Source toSource(Map<?, ?> sourceMap) {
        if (sourceMap == null || sourceMap.isEmpty()) {
            return null;
        }
        String type = (String) sourceMap.get("type");
        if (type == null) {
            return null;
        }
        String value = (String) sourceMap.get("value");
        String mimeType = (String) sourceMap.get("mimeType");
        return switch (type) {
            case "data" ->
                    value != null && mimeType != null
                            ? Base64Source.builder().data(value).mediaType(mimeType).build()
                            : null;
            case "url" -> value != null ? URLSource.builder().url(value).build() : null;
            default -> null;
        };
    }
}
