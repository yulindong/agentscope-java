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

import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEvent.InterruptItem;
import io.agentscope.core.agui.event.AguiEvent.RunFinishedOutcome;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts AgentScope v2 fine-grained {@link AgentEvent}s to AG-UI protocol
 * {@link AguiEvent}s.
 *
 * <p>This converter maps the 28 typed AgentEvent subclasses to AG-UI events,
 * eliminating the need for manual ContentBlock parsing. Key mappings:
 * <ul>
 *   <li>TextBlock* → TEXT_MESSAGE_START/CONTENT/END</li>
 *   <li>ThinkingBlock* → REASONING_MESSAGE_START/CONTENT/END (if enabled)</li>
 *   <li>ToolCall* → TOOL_CALL_START/ARGS/END</li>
 *   <li>ToolResult* → TOOL_CALL_RESULT (accumulates deltas)</li>
 *   <li>AgentStart → RUN_STARTED</li>
 *   <li>AgentEnd → RUN_FINISHED</li>
 *   <li>RequireUserConfirm → RUN_FINISHED with interrupt outcome</li>
 * </ul>
 */
public class AguiEventConverter {

    private final AguiAdapterConfig config;

    // Accumulates ToolResultTextDelta deltas per toolCallId across sequential events
    private final Map<String, StringBuilder> toolResultAccumulators = new HashMap<>();

    public AguiEventConverter(AguiAdapterConfig config) {
        this.config = config;
    }

    /**
     * Convert an AgentScope v2 {@link AgentEvent} to zero or more AG-UI events.
     *
     * @param event    the v2 agent event
     * @param threadId the AG-UI thread ID
     * @param runId    the AG-UI run ID
     * @return list of AG-UI events (may be empty for internal events)
     */
    public List<AguiEvent> convert(AgentEvent event, String threadId, String runId) {
        if (event instanceof AgentStartEvent e) {
            return convertAgentStart(e, threadId, runId);
        } else if (event instanceof AgentEndEvent e) {
            return convertAgentEnd(e, threadId, runId);
        } else if (event instanceof TextBlockStartEvent e) {
            return convertTextBlockStart(e, threadId, runId);
        } else if (event instanceof TextBlockDeltaEvent e) {
            return convertTextBlockDelta(e, threadId, runId);
        } else if (event instanceof TextBlockEndEvent e) {
            return convertTextBlockEnd(e, threadId, runId);
        } else if (event instanceof ThinkingBlockStartEvent e) {
            return convertThinkingBlockStart(e, threadId, runId);
        } else if (event instanceof ThinkingBlockDeltaEvent e) {
            return convertThinkingBlockDelta(e, threadId, runId);
        } else if (event instanceof ThinkingBlockEndEvent e) {
            return convertThinkingBlockEnd(e, threadId, runId);
        } else if (event instanceof ToolCallStartEvent e) {
            return convertToolCallStart(e, threadId, runId);
        } else if (event instanceof ToolCallDeltaEvent e) {
            return convertToolCallDelta(e, threadId, runId);
        } else if (event instanceof ToolCallEndEvent e) {
            return convertToolCallEnd(e, threadId, runId);
        } else if (event instanceof ToolResultTextDeltaEvent e) {
            return accumulateToolResultDelta(e);
        } else if (event instanceof ToolResultEndEvent e) {
            return convertToolResultEnd(e, threadId, runId);
        } else if (event instanceof ExceedMaxItersEvent e) {
            return convertExceedMaxIters(e, threadId, runId);
        } else if (event instanceof RequireUserConfirmEvent e) {
            return convertRequireUserConfirm(e, threadId, runId);
        } else if (event instanceof HintBlockEvent e) {
            return convertHintBlock(e, threadId, runId);
        } else if (event instanceof SubagentExposedEvent e) {
            return convertSubagentExposed(e, threadId, runId);
        } else if (event instanceof CustomEvent e) {
            return convertCustom(e, threadId, runId);
        }
        // AgentResultEvent, ModelCallStartEvent, ModelCallEndEvent,
        // DataBlockStartEvent/DeltaEvent/EndEvent, ToolResultDataDeltaEvent,
        // ToolResultStartEvent, RequireExternalExecutionEvent,
        // UserConfirmResultEvent, ExternalExecutionResultEvent, RequestStopEvent
        // are silently ignored (no AG-UI mapping or handled elsewhere)
        return List.of();
    }

    // ==================== Lifecycle events ====================

    private List<AguiEvent> convertAgentStart(AgentStartEvent e, String threadId, String runId) {
        return List.of(new AguiEvent.RunStarted(threadId, runId));
    }

    private List<AguiEvent> convertAgentEnd(AgentEndEvent e, String threadId, String runId) {
        return List.of(new AguiEvent.RunFinished(threadId, runId));
    }

    // ==================== Text message events ====================

    private List<AguiEvent> convertTextBlockStart(
            TextBlockStartEvent e, String threadId, String runId) {
        return List.of(
                new AguiEvent.TextMessageStart(threadId, runId, e.getBlockId(), "assistant"));
    }

    private List<AguiEvent> convertTextBlockDelta(
            TextBlockDeltaEvent e, String threadId, String runId) {
        String delta = e.getDelta();
        if (delta == null || delta.isEmpty()) {
            return List.of();
        }
        return List.of(new AguiEvent.TextMessageContent(threadId, runId, e.getBlockId(), delta));
    }

    private List<AguiEvent> convertTextBlockEnd(
            TextBlockEndEvent e, String threadId, String runId) {
        return List.of(new AguiEvent.TextMessageEnd(threadId, runId, e.getBlockId()));
    }

    // ==================== Thinking/Reasoning events ====================

    private List<AguiEvent> convertThinkingBlockStart(
            ThinkingBlockStartEvent e, String threadId, String runId) {
        if (!config.isEnableReasoning()) {
            return List.of();
        }
        return List.of(
                new AguiEvent.ReasoningMessageStart(threadId, runId, e.getBlockId(), "reasoning"));
    }

    private List<AguiEvent> convertThinkingBlockDelta(
            ThinkingBlockDeltaEvent e, String threadId, String runId) {
        if (!config.isEnableReasoning()) {
            return List.of();
        }
        String delta = e.getDelta();
        if (delta == null || delta.isEmpty()) {
            return List.of();
        }
        return List.of(
                new AguiEvent.ReasoningMessageContent(threadId, runId, e.getBlockId(), delta));
    }

    private List<AguiEvent> convertThinkingBlockEnd(
            ThinkingBlockEndEvent e, String threadId, String runId) {
        if (!config.isEnableReasoning()) {
            return List.of();
        }
        return List.of(new AguiEvent.ReasoningMessageEnd(threadId, runId, e.getBlockId()));
    }

    // ==================== Tool call events ====================

    private List<AguiEvent> convertToolCallStart(
            ToolCallStartEvent e, String threadId, String runId) {
        return List.of(
                new AguiEvent.ToolCallStart(
                        threadId, runId, e.getToolCallId(), e.getToolCallName()));
    }

    private List<AguiEvent> convertToolCallDelta(
            ToolCallDeltaEvent e, String threadId, String runId) {
        String delta = e.getDelta();
        if (delta == null || delta.isEmpty()) {
            return List.of();
        }
        return List.of(new AguiEvent.ToolCallArgs(threadId, runId, e.getToolCallId(), delta));
    }

    private List<AguiEvent> convertToolCallEnd(ToolCallEndEvent e, String threadId, String runId) {
        return List.of(new AguiEvent.ToolCallEnd(threadId, runId, e.getToolCallId()));
    }

    // ==================== Tool result events ====================

    private List<AguiEvent> accumulateToolResultDelta(ToolResultTextDeltaEvent e) {
        String delta = e.getDelta();
        if (delta == null || delta.isEmpty()) {
            return List.of();
        }
        toolResultAccumulators
                .computeIfAbsent(e.getToolCallId(), k -> new StringBuilder())
                .append(delta);
        return List.of();
    }

    private List<AguiEvent> convertToolResultEnd(
            ToolResultEndEvent e, String threadId, String runId) {
        String toolCallId = e.getToolCallId();
        StringBuilder acc = toolResultAccumulators.remove(toolCallId);
        String content = (acc != null && !acc.isEmpty()) ? acc.toString() : null;
        return List.of(
                new AguiEvent.ToolCallResult(
                        threadId, runId, toolCallId, content, "tool", e.getReplyId()));
    }

    // ==================== Error events ====================

    private List<AguiEvent> convertExceedMaxIters(
            ExceedMaxItersEvent e, String threadId, String runId) {
        return List.of(
                new AguiEvent.RunError(
                        threadId,
                        runId,
                        "Agent exceeded max iterations: "
                                + e.getCurrentIter()
                                + "/"
                                + e.getMaxIters()));
    }

    // ==================== HITL events ====================

    private List<AguiEvent> convertRequireUserConfirm(
            RequireUserConfirmEvent e, String threadId, String runId) {
        List<InterruptItem> interrupts = new ArrayList<>();
        if (e.getToolCalls() != null) {
            for (var tc : e.getToolCalls()) {
                interrupts.add(
                        new InterruptItem(
                                tc.getId(),
                                "tool_call",
                                "Tool requires confirmation: " + tc.getName(),
                                tc.getId(),
                                null,
                                null,
                                null));
            }
        }
        if (interrupts.isEmpty()) {
            // Fallback: create a generic interrupt
            interrupts.add(
                    new InterruptItem(
                            java.util.UUID.randomUUID().toString(),
                            "input_required",
                            "User confirmation required for tool execution",
                            null,
                            null,
                            null,
                            null));
        }
        return List.of(
                new AguiEvent.RunFinished(
                        threadId, runId, null, new RunFinishedOutcome.Interrupt(interrupts)));
    }

    // ==================== Informational events ====================

    private List<AguiEvent> convertHintBlock(HintBlockEvent e, String threadId, String runId) {
        String hint = e.getHint();
        if (hint == null || hint.isEmpty()) {
            return List.of();
        }
        String blockId = e.getBlockId();
        return List.of(
                new AguiEvent.TextMessageStart(threadId, runId, blockId, "system"),
                new AguiEvent.TextMessageContent(threadId, runId, blockId, hint),
                new AguiEvent.TextMessageEnd(threadId, runId, blockId));
    }

    private List<AguiEvent> convertSubagentExposed(
            SubagentExposedEvent e, String threadId, String runId) {
        return List.of(
                new AguiEvent.Raw(
                        threadId,
                        runId,
                        Map.of(
                                "type", "subagent_exposed",
                                "subagentId", e.getSubagentId(),
                                "agentId", e.getAgentId(),
                                "sessionId", e.getSessionId(),
                                "label", e.getLabel()),
                        "agentscope"));
    }

    private List<AguiEvent> convertCustom(CustomEvent e, String threadId, String runId) {
        return List.of(new AguiEvent.Custom(threadId, runId, e.getName(), e.getValue()));
    }

    /**
     * Resets the internal tool result accumulator state.
     * Call this between runs to ensure clean state.
     */
    public void reset() {
        toolResultAccumulators.clear();
    }
}
