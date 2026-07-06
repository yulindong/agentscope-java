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
package io.agentscope.core.agui.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a message in the AG-UI protocol.
 *
 * <p>Messages are the primary communication unit in the AG-UI protocol.
 * They contain content, role information, and optionally tool calls or tool call IDs.
 *
 * <p>Message roles:
 * <ul>
 *   <li>user - Messages from the user</li>
 *   <li>assistant - Messages from the AI assistant</li>
 *   <li>system - System instructions</li>
 *   <li>tool - Tool execution results</li>
 *   <li>developer - Development or debugging messages</li>
 *   <li>activity - Frontend-only structured UI messages</li>
 *   <li>reasoning - Agent internal reasoning messages</li>
 * </ul>
 *
 * <p>Content types:
 * <ul>
 *   <li>{@code string} - Plain text content (assistant, system, tool, developer, reasoning)</li>
 *   <li>{@code InputContent[]} - Multimodal content for user messages (text, image, audio, video, document)</li>
 *   <li>{@code Record<string, any>} - Structured payload for activity messages</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AguiMessage {

    private final String id;
    private final String role;
    private final Object content;
    private final List<AguiToolCall> toolCalls;
    private final String toolCallId;

    /**
     * Creates a new AguiMessage.
     *
     * @param id The unique message ID
     * @param role The message role (user, assistant, system, tool, developer, activity, reasoning)
     * @param content The message content ({@code String}, {@code List<?>} or {@code Map<?, ?>})
     * @param toolCalls Tool calls for assistant messages (optional)
     * @param toolCallId Tool call ID for tool messages (optional)
     */
    @JsonCreator
    public AguiMessage(
            @JsonProperty("id") String id,
            @JsonProperty("role") String role,
            @JsonProperty("content") Object content,
            @JsonProperty("toolCalls") List<AguiToolCall> toolCalls,
            @JsonProperty("toolCallId") String toolCallId) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.role = Objects.requireNonNull(role, "role cannot be null");
        this.content = content;
        this.toolCalls =
                toolCalls != null
                        ? Collections.unmodifiableList(toolCalls)
                        : Collections.emptyList();
        this.toolCallId = toolCallId;
    }

    /**
     * Creates a simple user message with text content.
     *
     * @param id The message ID
     * @param content The message content
     * @return A new user message
     */
    public static AguiMessage userMessage(String id, String content) {
        return new AguiMessage(id, "user", content, null, null);
    }

    /**
     * Creates a user message with multimodal content.
     *
     * @param id The message ID
     * @param content The multimodal content fragments
     * @return A new user message
     */
    public static AguiMessage userMessage(String id, List<?> content) {
        return new AguiMessage(id, "user", content, null, null);
    }

    /**
     * Creates a simple assistant message.
     *
     * @param id The message ID
     * @param content The message content
     * @return A new assistant message
     */
    public static AguiMessage assistantMessage(String id, String content) {
        return new AguiMessage(id, "assistant", content, null, null);
    }

    /**
     * Creates a system message.
     *
     * @param id The message ID
     * @param content The message content
     * @return A new system message
     */
    public static AguiMessage systemMessage(String id, String content) {
        return new AguiMessage(id, "system", content, null, null);
    }

    /**
     * Creates a tool result message.
     *
     * @param id The message ID
     * @param toolCallId The ID of the tool call this is responding to
     * @param content The tool result content
     * @return A new tool message
     */
    public static AguiMessage toolMessage(String id, String toolCallId, String content) {
        return new AguiMessage(id, "tool", content, null, toolCallId);
    }

    /**
     * Creates a developer message.
     *
     * @param id The message ID
     * @param content The message content
     * @return A new developer message
     */
    public static AguiMessage developerMessage(String id, String content) {
        return new AguiMessage(id, "developer", content, null, null);
    }

    /**
     * Creates an activity message with a structured payload.
     *
     * @param id The message ID
     * @param content The structured activity payload
     * @return A new activity message
     */
    public static AguiMessage activityMessage(String id, Map<String, Object> content) {
        return new AguiMessage(id, "activity", content, null, null);
    }

    /**
     * Creates a reasoning message.
     *
     * @param id The message ID
     * @param content The reasoning content
     * @return A new reasoning message
     */
    public static AguiMessage reasoningMessage(String id, String content) {
        return new AguiMessage(id, "reasoning", content, null, null);
    }

    /**
     * Get the message ID.
     *
     * @return The message ID
     */
    public String getId() {
        return id;
    }

    /**
     * Get the message role.
     *
     * @return The role (user, assistant, system, tool, developer, activity, reasoning)
     */
    public String getRole() {
        return role;
    }

    /**
     * Get the raw message content.
     *
     * <p>According to the AG-UI specification, the content may be:
     * <ul>
     *   <li>{@code String} for plain text messages</li>
     *   <li>{@code List<?>} for multimodal user message content</li>
     *   <li>{@code Map<String, Object>} for structured activity payloads</li>
     * </ul>
     *
     * @return The content, may be null
     */
    public Object getContent() {
        return content;
    }

    /**
     * Get the message content as a plain string.
     *
     * @return The content as a string, or null if the content is not a string or is null
     */
    public String getContentAsString() {
        return content instanceof String ? (String) content : null;
    }

    /**
     * Get the message content as a list.
     *
     * @return The content as a list, or null if the content is not a list
     */
    @SuppressWarnings("unchecked")
    public List<Object> getContentAsList() {
        return content instanceof List ? (List<Object>) content : null;
    }

    /**
     * Get the message content as a map.
     *
     * @return The content as a map, or null if the content is not a map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getContentAsMap() {
        return content instanceof Map ? (Map<String, Object>) content : null;
    }

    /**
     * Get the tool calls (for assistant messages).
     *
     * @return The tool calls as an immutable list, empty if none
     */
    public List<AguiToolCall> getToolCalls() {
        return toolCalls;
    }

    /**
     * Get the tool call ID (for tool messages).
     *
     * @return The tool call ID, or null if not a tool message
     */
    public String getToolCallId() {
        return toolCallId;
    }

    /**
     * Check if this is a user message.
     *
     * @return true if role is "user"
     */
    public boolean isUserMessage() {
        return "user".equals(role);
    }

    /**
     * Check if this is an assistant message.
     *
     * @return true if role is "assistant"
     */
    public boolean isAssistantMessage() {
        return "assistant".equals(role);
    }

    /**
     * Check if this is a system message.
     *
     * @return true if role is "system"
     */
    public boolean isSystemMessage() {
        return "system".equals(role);
    }

    /**
     * Check if this is a tool message.
     *
     * @return true if role is "tool"
     */
    public boolean isToolMessage() {
        return "tool".equals(role);
    }

    /**
     * Check if this is a developer message.
     *
     * @return true if role is "developer"
     */
    public boolean isDeveloperMessage() {
        return "developer".equals(role);
    }

    /**
     * Check if this is an activity message.
     *
     * @return true if role is "activity"
     */
    public boolean isActivityMessage() {
        return "activity".equals(role);
    }

    /**
     * Check if this is a reasoning message.
     *
     * @return true if role is "reasoning"
     */
    public boolean isReasoningMessage() {
        return "reasoning".equals(role);
    }

    /**
     * Check if this message has tool calls.
     *
     * @return true if tool calls are present
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    @Override
    public String toString() {
        return "AguiMessage{id='"
                + id
                + "', role='"
                + role
                + "', content='"
                + content
                + "', toolCalls="
                + toolCalls
                + ", toolCallId='"
                + toolCallId
                + "'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AguiMessage that = (AguiMessage) o;
        return Objects.equals(id, that.id)
                && Objects.equals(role, that.role)
                && Objects.equals(content, that.content)
                && Objects.equals(toolCalls, that.toolCalls)
                && Objects.equals(toolCallId, that.toolCallId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, role, content, toolCalls, toolCallId);
    }
}
