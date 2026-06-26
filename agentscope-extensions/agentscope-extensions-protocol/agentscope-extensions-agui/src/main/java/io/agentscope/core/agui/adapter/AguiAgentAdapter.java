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
package io.agentscope.core.agui.adapter;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.converter.AguiEventConverter;
import io.agentscope.core.agui.converter.AguiMessageConverter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEvent.RunFinishedOutcome;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Adapter that bridges AgentScope v2 agents to the AG-UI protocol.
 *
 * <p>Supported agent types:
 * <ul>
 *   <li>{@link HarnessAgent} — delegates to {@link HarnessAgent#streamEvents(List, RuntimeContext)}
 *       with sandbox lifecycle management</li>
 *   <li>{@link ReActAgent} — delegates to {@link ReActAgent#streamEvents(List, RuntimeContext)}</li>
 * </ul>
 *
 * <p>This adapter converts AG-UI protocol inputs to AgentScope messages,
 * invokes the agent's fine-grained event stream, and converts the v2
 * {@link AgentEvent}s back to AG-UI protocol events.
 *
 * <p><b>Event Mapping (v2 AgentEvent → AG-UI):</b>
 * <ul>
 *   <li>AgentStartEvent → RUN_STARTED</li>
 *   <li>AgentEndEvent → RUN_FINISHED</li>
 *   <li>TextBlockStart/Delta/EndEvent → TEXT_MESSAGE_START/CONTENT/END</li>
 *   <li>ThinkingBlockStart/Delta/EndEvent → REASONING_MESSAGE_START/CONTENT/END</li>
 *   <li>ToolCallStart/Delta/EndEvent → TOOL_CALL_START/ARGS/END</li>
 *   <li>ToolResultTextDelta + ToolResultEndEvent → TOOL_CALL_RESULT</li>
 *   <li>ExceedMaxItersEvent → RUN_ERROR</li>
 *   <li>RequireUserConfirmEvent → RUN_FINISHED with interrupt outcome</li>
 *   <li>HintBlockEvent → TEXT_MESSAGE_START/CONTENT/END (system role)</li>
 *   <li>SubagentExposedEvent → RAW event</li>
 *   <li>CustomEvent → CUSTOM event</li>
 * </ul>
 *
 * <p>This replaces the previous v1 adapter that used the deprecated
 * {@code Agent.stream()} API with coarse-grained {@code Event} types.
 */
public class AguiAgentAdapter {

    private static final Logger logger = LoggerFactory.getLogger(AguiAgentAdapter.class);

    private final Agent agent;
    private final AguiAdapterConfig config;
    private final AguiMessageConverter messageConverter;

    /**
     * Creates a new AguiAgentAdapter.
     *
     * @param agent  The agent to adapt (must be a {@link HarnessAgent} or {@link ReActAgent}
     *               instance)
     * @param config The adapter configuration
     */
    public AguiAgentAdapter(Agent agent, AguiAdapterConfig config) {
        this.agent = Objects.requireNonNull(agent, "agent cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.messageConverter = new AguiMessageConverter();
    }

    /**
     * Run the agent with AG-UI protocol input using the v2 event stream.
     *
     * <p>This method converts the input messages, invokes the agent's
     * {@code streamEvents} API, and emits AG-UI protocol events.
     * Supports both {@link HarnessAgent} and {@link ReActAgent} instances.
     *
     * @param input The AG-UI run input
     * @return A Flux of AG-UI events
     */
    public Flux<AguiEvent> run(RunAgentInput input) {
        String threadId = input.getThreadId();
        String runId = input.getRunId();

        return Flux.defer(
                        () -> {
                            // Build RuntimeContext from AG-UI input
                            RuntimeContext runtimeContext = buildRuntimeContext(input);

                            // Check if this is a messages snapshot request:
                            // threadId has value, messages is empty, and resume is empty
                            if (isMessagesSnapshotRequest(input)) {
                                return buildMessagesSnapshotFlux(
                                        input, runtimeContext, threadId, runId);
                            }

                            // Convert AG-UI messages to AgentScope messages
                            List<Msg> msgs = messageConverter.toMsgList(input.getMessages());

                            // Create the event converter for this run
                            AguiEventConverter converter = new AguiEventConverter(config);

                            // Resolve the event stream based on agent type
                            Flux<AgentEvent> eventStream = resolveEventStream(msgs, runtimeContext);

                            // Stream v2 AgentEvents and convert to AG-UI events
                            return eventStream
                                    .concatMapIterable(
                                            event -> {
                                                List<AguiEvent> aguiEvents =
                                                        converter.convert(event, threadId, runId);
                                                if (logger.isTraceEnabled()) {
                                                    logger.trace(
                                                            "Converted {} → {} AG-UI event(s)",
                                                            event.getClass().getSimpleName(),
                                                            aguiEvents.size());
                                                }
                                                return aguiEvents;
                                            })
                                    // Map HITL events to interrupt outcomes
                                    .concatMap(
                                            aguiEvent -> handleInterruptOutcome(aguiEvent, input))
                                    // Log each AG-UI event as JSON for debugging
                                    .doOnNext(
                                            aguiEvent -> {
                                                if (logger.isDebugEnabled()) {
                                                    try {
                                                        logger.debug(
                                                                "AG-UI event: {}",
                                                                JsonUtils.getJsonCodec()
                                                                        .toJson(aguiEvent));
                                                    } catch (Exception e) {
                                                        logger.debug(
                                                                "AG-UI event (serialization"
                                                                        + " failed): type={},"
                                                                        + " threadId={}, runId={}",
                                                                aguiEvent.getType(),
                                                                aguiEvent.getThreadId(),
                                                                aguiEvent.getRunId());
                                                    }
                                                }
                                            });
                        })
                .onErrorResume(
                        error -> {
                            logger.error("Error during agent run: {}", error.getMessage(), error);
                            String errorMessage =
                                    error.getMessage() != null
                                            ? error.getMessage()
                                            : error.getClass().getSimpleName();
                            return Flux.just(
                                    new AguiEvent.RunError(threadId, runId, errorMessage),
                                    new AguiEvent.RunFinished(threadId, runId));
                        });
    }

    /**
     * Builds a {@link RuntimeContext} from the AG-UI {@link RunAgentInput}.
     *
     * <p>Mappings:
     * <ul>
     *   <li>{@code threadId} → {@code sessionId}</li>
     *   <li>{@code forwardedProps["userId"]} → {@code userId}</li>
     *   <li>{@code forwardedProps} → string extras</li>
     * </ul>
     *
     * @param input The AG-UI run input
     * @return A constructed RuntimeContext
     */
    private RuntimeContext buildRuntimeContext(RunAgentInput input) {
        RuntimeContext.Builder ctxBuilder = RuntimeContext.builder();

        // Map threadId to sessionId
        ctxBuilder.sessionId(input.getThreadId());

        // Map userId from forwardedProps if present
        Object userId = input.getForwardedProp("userId");
        if (userId instanceof String userIdStr) {
            ctxBuilder.userId(userIdStr);
        }

        // Put forwarded props as string extras (excluding userId which is already mapped)
        if (input.getForwardedProps() != null && !input.getForwardedProps().isEmpty()) {
            for (Map.Entry<String, Object> entry : input.getForwardedProps().entrySet()) {
                if (!"userId".equals(entry.getKey())) {
                    ctxBuilder.put(entry.getKey(), entry.getValue());
                }
            }
        }

        return ctxBuilder.build();
    }

    /**
     * Resolves the {@link AgentEvent} stream for the configured agent.
     *
     * <p>Supports:
     * <ul>
     *   <li>{@link HarnessAgent} — uses {@link HarnessAgent#streamEvents(List, RuntimeContext)}</li>
     *   <li>{@link ReActAgent} — uses {@link ReActAgent#streamEvents(List, RuntimeContext)}</li>
     * </ul>
     *
     * @param msgs the input messages
     * @param runtimeContext the runtime context for this run
     * @return the event stream
     * @throws IllegalStateException if the agent type is not supported
     */
    private Flux<AgentEvent> resolveEventStream(List<Msg> msgs, RuntimeContext runtimeContext) {
        if (agent instanceof HarnessAgent harnessAgent) {
            logger.debug("Using HarnessAgent streamEvents API");
            return harnessAgent.streamEvents(msgs, runtimeContext);
        }
        if (agent instanceof ReActAgent reactAgent) {
            logger.debug("Using ReActAgent streamEvents API");
            return reactAgent.streamEvents(msgs, runtimeContext);
        }
        return Flux.error(
                new IllegalStateException(
                        "Agent must be a HarnessAgent or ReActAgent instance to use v2"
                                + " event streaming. Got: "
                                + agent.getClass().getName()));
    }

    /**
     * Handles interrupt outcomes emitted by the converter.
     *
     * <p>When the converter emits a {@link RunFinished} with an
     * {@link RunFinishedOutcome.Interrupt} outcome (e.g. from
     * {@link RequireUserConfirmEvent}), we need to pause the stream
     * and wait for the user to provide input via resume.
     *
     * <p>Currently, interrupt outcomes are passed through directly.
     * In a full HITL implementation, this method would subscribe to a
     * resume signal from the front-end and continue the agent execution.
     */
    private Flux<AguiEvent> handleInterruptOutcome(AguiEvent aguiEvent, RunAgentInput input) {

        // Pass through non-interrupt events
        if (!(aguiEvent instanceof AguiEvent.RunFinished rf)) {
            return Flux.just(aguiEvent);
        }
        if (!(rf.outcome() instanceof RunFinishedOutcome.Interrupt)) {
            return Flux.just(aguiEvent);
        }

        // For interrupt outcomes, check if there's resume data in the input
        if (input.getResume() != null && !input.getResume().isEmpty()) {
            // This is a resumed run - the agent has already processed the
            // resume data before this call. Pass through the RunFinished.
            logger.debug("Resumed run with interrupt outcome, passing through");
        }

        return Flux.just(aguiEvent);
    }

    /**
     * Create a resume-aware run for HITL (Human-in-the-Loop) scenarios.
     *
     * <p>This method handles the full lifecycle of an agent run that may
     * be interrupted for user confirmation. It:
     * <ol>
     *   <li>Checks for resume items in the input</li>
     *   <li>Injects resume data as UserConfirmResultEvent or
     *       ExternalExecutionResultEvent signals into the agent context</li>
     *   <li>Resumes the agent execution</li>
     * </ol>
     *
     * @param input  The run input with optional resume data
     * @param config The adapter configuration
     * @param agent  The agent to run (must be a {@link HarnessAgent} or {@link ReActAgent})
     * @return AG-UI event stream
     */
    public static Flux<AguiEvent> runWithResume(
            RunAgentInput input, AguiAdapterConfig config, Agent agent) {

        String threadId = input.getThreadId();
        String runId = input.getRunId();

        // If there's resume data, emit it as context events before agent execution
        List<AguiEvent> resumeEvents = new ArrayList<>();
        if (input.getResume() != null && !input.getResume().isEmpty()) {
            for (var resumeItem : input.getResume()) {
                resumeEvents.add(
                        new AguiEvent.Raw(
                                threadId,
                                runId,
                                Map.of(
                                        "type", "resume",
                                        "interruptId", resumeItem.interruptId(),
                                        "status", resumeItem.status(),
                                        "payload", resumeItem.payload()),
                                "agentscope"));
            }
        }

        if (!resumeEvents.isEmpty()) {
            return Flux.concat(
                    Flux.fromIterable(resumeEvents),
                    new AguiAgentAdapter(agent, config).run(input));
        }

        return new AguiAgentAdapter(agent, config).run(input);
    }

    /**
     * Check if the input is a messages snapshot request.
     *
     * <p>A messages snapshot is triggered when the input has a threadId with a value,
     * but no messages and no resume items. This means the client is requesting the
     * full message history for the given thread.
     *
     * @param input The AG-UI run input
     * @return true if this is a messages snapshot request
     */
    private boolean isMessagesSnapshotRequest(RunAgentInput input) {
        String threadId = input.getThreadId();
        boolean hasThreadId = threadId != null && !threadId.isEmpty();
        boolean noMessages = input.getMessages() == null || input.getMessages().isEmpty();
        boolean noResume = input.getResume() == null || input.getResume().isEmpty();
        return hasThreadId && noMessages && noResume;
    }

    /**
     * Build a Flux of AG-UI events for a messages snapshot request.
     *
     * <p>This retrieves the message history from the agent's {@link AgentState}
     * context, converts them to AG-UI messages, and emits:
     * {@code RunStarted → MessagesSnapshot → RunFinished}.
     *
     * @param input The AG-UI run input
     * @param runtimeContext The built runtime context
     * @param threadId The thread ID
     * @param runId The run ID
     * @return A Flux of AG-UI events
     */
    private Flux<AguiEvent> buildMessagesSnapshotFlux(
            RunAgentInput input, RuntimeContext runtimeContext, String threadId, String runId) {

        logger.debug("Messages snapshot request: threadId={}, runId={}", threadId, runId);

        List<AguiEvent> events = new ArrayList<>();
        events.add(new AguiEvent.RunStarted(threadId, runId));

        // Get message history from AgentState
        AgentState agentState = getAgentState(runtimeContext);
        if (agentState != null) {
            List<Msg> contextMessages = agentState.getContext();
            if (contextMessages != null && !contextMessages.isEmpty()) {
                List<AguiMessage> aguiMessages =
                        messageConverter.toAguiMessageList(contextMessages);
                events.add(new AguiEvent.MessagesSnapshot(threadId, runId, aguiMessages));
                logger.debug(
                        "Messages snapshot: {} message(s) from AgentState context",
                        aguiMessages.size());
            } else {
                events.add(new AguiEvent.MessagesSnapshot(threadId, runId, List.of()));
                logger.debug("Messages snapshot: empty context");
            }
        } else {
            events.add(new AguiEvent.MessagesSnapshot(threadId, runId, List.of()));
            logger.debug("Messages snapshot: AgentState not available");
        }

        events.add(new AguiEvent.RunFinished(threadId, runId));
        return Flux.fromIterable(events);
    }

    /**
     * Get the {@link AgentState} from the agent using the given {@link RuntimeContext}.
     *
     * <p>Supports:
     * <ul>
     *   <li>{@link HarnessAgent} — delegates to
     *       {@code getDelegate().getAgentState(RuntimeContext)}</li>
     *   <li>{@link ReActAgent} — uses {@code getAgentState(RuntimeContext)}</li>
     * </ul>
     *
     * @param runtimeContext The runtime context containing session/user info
     * @return The AgentState, or null if not available
     */
    private AgentState getAgentState(RuntimeContext runtimeContext) {
        if (agent instanceof HarnessAgent harnessAgent) {
            return harnessAgent.getDelegate().getAgentState(runtimeContext);
        }
        if (agent instanceof ReActAgent reactAgent) {
            return reactAgent.getAgentState(runtimeContext);
        }
        logger.warn(
                "Cannot get AgentState: agent type {} does not support it",
                agent.getClass().getName());
        return null;
    }

    /**
     * Get the underlying agent instance.
     *
     * @return The agent
     */
    public Agent getAgent() {
        return agent;
    }
}
