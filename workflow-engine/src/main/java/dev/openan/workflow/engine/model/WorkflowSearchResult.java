/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the License); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine.model;

import dev.openan.workflow.engine.registry.LoadPsop;
import java.util.Collections;
import java.util.List;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Summary of a PSOP workflow returned by the search endpoint.
 *
 * <p>Mirrors the Python orchestration center's {@code WorkflowSearchResult}. Returned by {@link
 * LoadPsop#search}. To get the full workflow with steps, take {@code getWorkflowId()} and call
 * {@link LoadPsop#load}.
 */
@Data
@NoArgsConstructor
@Builder
public class WorkflowSearchResult {
  private String workflowId;
  private String workflowType;
  private String name;
  private String description;
  @Builder.Default private List<String> tags = List.of();
  private String createdAt;
  @Builder.Default private double score = 1.0;
  private String userIntent;
  private String relatedPreflow;
  private String tasksSummary;

  /** All-args constructor with defensive copies; Lombok's builder routes through it. */
  public WorkflowSearchResult(
      String workflowId,
      String workflowType,
      String name,
      String description,
      List<String> tags,
      String createdAt,
      double score,
      String userIntent,
      String relatedPreflow,
      String tasksSummary) {
    this.workflowId = workflowId;
    this.workflowType = workflowType;
    this.name = name;
    this.description = description;
    this.tags = tags == null ? List.of() : List.copyOf(tags);
    this.createdAt = createdAt;
    this.score = score;
    this.userIntent = userIntent;
    this.relatedPreflow = relatedPreflow;
    this.tasksSummary = tasksSummary;
  }

  /** Unmodifiable view; tags are snapshots. */
  public List<String> getTags() {
    return tags == null ? null : Collections.unmodifiableList(tags);
  }

  /** Stores a defensive copy of the supplied tag list. */
  public void setTags(List<String> tags) {
    this.tags = tags == null ? List.of() : List.copyOf(tags);
  }
}
