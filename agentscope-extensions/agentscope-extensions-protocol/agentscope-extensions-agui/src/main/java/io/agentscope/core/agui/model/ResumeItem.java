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
import java.util.Objects;

/**
 * Represents a resume item in the AG-UI interrupt protocol.
 *
 * <p>Per AG-UI interrupt protocol: when a run is interrupted, the client resumes
 * by starting a new run whose {@link RunAgentInput} includes a {@code resume}
 * array addressing every open interrupt.
 *
 * @param interruptId Must reference an {@code id} from the interrupted run's interrupts array
 * @param status Either "resolved" (user responded) or "cancelled" (user abandoned)
 * @param payload Optional response payload, validated against the interrupt's responseSchema
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResumeItem(
        @JsonProperty("interruptId") String interruptId,
        @JsonProperty("status") String status,
        @JsonProperty("payload") Object payload) {

    /**
     * Compact constructor with validation.
     */
    @JsonCreator
    public ResumeItem {
        Objects.requireNonNull(interruptId, "interruptId cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        // payload is optional
    }

    /**
     * Convenience constructor without payload.
     *
     * @param interruptId The interrupt ID to resume
     * @param status The resume status ("resolved" or "cancelled")
     */
    public ResumeItem(String interruptId, String status) {
        this(interruptId, status, null);
    }
}
