/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
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
package org.finos.fluxnova.bpm.engine.impl.bpmn.behavior;

import java.util.ArrayList;
import java.util.List;

import org.finos.fluxnova.bpm.engine.BadUserRequestException;
import org.finos.fluxnova.bpm.engine.delegate.Expression;
import org.finos.fluxnova.bpm.engine.impl.bpmn.helper.BpmnProperties;
import org.finos.fluxnova.bpm.model.bpmn.AdHocOrdering;
import org.finos.fluxnova.bpm.engine.impl.bpmn.helper.CompensationUtil;
import org.finos.fluxnova.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.finos.fluxnova.bpm.engine.impl.pvm.delegate.ActivityExecution;
import org.finos.fluxnova.bpm.engine.impl.pvm.delegate.CompositeActivityBehavior;
import org.finos.fluxnova.bpm.engine.impl.pvm.process.ActivityImpl;
import org.finos.fluxnova.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;

/**
 * Implementation of the BPMN 2.0 ad-hoc subprocess.
 *
 * Unlike a regular subprocess, no child activity is auto-started on entry.
 * Activities are triggered explicitly via
 * {@link #triggerChildActivity(ActivityExecution, String)}. The subprocess
 * completes when the completion condition evaluates to {@code true} or when
 * force-completed via {@link #complete(ActivityExecution)}.
 */
public class AdHocSubProcessActivityBehavior extends AbstractBpmnActivityBehavior implements CompositeActivityBehavior {

  protected static final String NR_OF_ACTIVE_INSTANCES    = "nrOfActiveInstances";
  protected static final String NR_OF_COMPLETED_INSTANCES = "nrOfCompletedInstances";
  protected static final String COMPLETION_PENDING         = "adHocCompletionPending";

  /** Set by the parser when a completionCondition child element is present. */
  protected Expression completionConditionExpression;

  public void setCompletionConditionExpression(Expression expression) {
    this.completionConditionExpression = expression;
  }

  public Expression getCompletionConditionExpression() {
    return completionConditionExpression;
  }

  // -------------------------------------------------------------------------
  // execute — park the scope, initialise counters
  // -------------------------------------------------------------------------

  @Override
  public void execute(ActivityExecution execution) throws Exception {
    // Silent-return parking pattern (Decision 1): do NOT call executeActivity()
    // or leave(). The scope execution waits here until externally completed.
    execution.setVariableLocal(NR_OF_ACTIVE_INSTANCES,    0);
    execution.setVariableLocal(NR_OF_COMPLETED_INSTANCES, 0);
  }

  // -------------------------------------------------------------------------
  // triggerChildActivity — called by the Java/REST API
  // -------------------------------------------------------------------------

  /**
   * Starts a named direct-child activity inside this ad-hoc subprocess scope.
   *
   * @param scopeExecution the scope execution of the ad-hoc subprocess
   * @param activityId     id of the direct child activity to trigger
   */
  public void triggerChildActivity(ActivityExecution scopeExecution, String activityId) {
    ActivityImpl adHocActivity = (ActivityImpl) scopeExecution.getActivity();
    ActivityImpl childActivity  = adHocActivity.getChildActivity(activityId);

    if (childActivity == null) {
      throw new BadUserRequestException(
          "Activity '" + activityId + "' is not a direct child of ad-hoc subprocess '" + adHocActivity.getId() + "'");
    }

    // Reject if a drain is in progress (cancelRemainingInstances=false path)
    Boolean completionPending = (Boolean) scopeExecution.getVariableLocal(COMPLETION_PENDING);
    if (Boolean.TRUE.equals(completionPending)) {
      throw new BadUserRequestException(
          "Cannot trigger activity '" + activityId + "': ad-hoc subprocess is waiting to complete");
    }

    // Sequential ordering: only one active child allowed at a time (Decision 3)
    AdHocOrdering ordering = adHocActivity.getProperties().get(BpmnProperties.AD_HOC_ORDERING);
    if (AdHocOrdering.Sequential.equals(ordering)) {
      List<? extends PvmExecutionImpl> active = ((PvmExecutionImpl) scopeExecution).getNonEventScopeExecutions();
      if (!active.isEmpty()) {
        throw new BadUserRequestException(
            "Cannot trigger activity '" + activityId + "': sequential ad-hoc subprocess already has an active child");
      }
    }

    if (!childActivity.getIncomingTransitions().isEmpty()) {
      throw new BadUserRequestException(
          "Activity '" + activityId + "' has incoming sequence flows and cannot be triggered directly");
    }

    // Create a new concurrent child execution so the scope execution stays
    // parked on the ad-hoc subprocess, allowing further triggers and correct
    // lifecycle tracking. (createExecution is used instead of executeActivity so
    // the scope execution remains parked and parallel triggers work correctly.)
    ActivityExecution childExecution = scopeExecution.createExecution();
    ((ExecutionEntity) childExecution).setConcurrent(true);
    ((ExecutionEntity) childExecution).setScope(false);
    childExecution.executeActivity(childActivity);

    int active = intVar(scopeExecution, NR_OF_ACTIVE_INSTANCES);
    scopeExecution.setVariableLocal(NR_OF_ACTIVE_INSTANCES, active + 1);
  }

  // -------------------------------------------------------------------------
  // concurrentChildExecutionEnded — called when any child execution ends
  // -------------------------------------------------------------------------

  @Override
  public void concurrentChildExecutionEnded(ActivityExecution scopeExecution, ActivityExecution endedExecution) {
    // Counters increment only on terminal completions (Decision 5)
    endedExecution.remove();

    int active    = intVar(scopeExecution, NR_OF_ACTIVE_INSTANCES) - 1;
    int completed =            intVar(scopeExecution, NR_OF_COMPLETED_INSTANCES) + 1;
    scopeExecution.setVariableLocal(NR_OF_ACTIVE_INSTANCES,    active);
    scopeExecution.setVariableLocal(NR_OF_COMPLETED_INSTANCES, completed);

    scopeExecution.forceUpdate();

    Boolean completionPending = (Boolean) scopeExecution.getVariableLocal(COMPLETION_PENDING);

    if (Boolean.TRUE.equals(completionPending)) {
      // Draining after condition satisfied with cancelRemainingInstances=false
      if (active == 0) {
        leave(scopeExecution);
      }
      return;
    }

    if (completionConditionSatisfied(scopeExecution)) {
      Boolean cancelRemaining = scopeExecution.getActivity().getProperties().get(BpmnProperties.AD_HOC_CANCEL_REMAINING_INSTANCES);
      if (!Boolean.FALSE.equals(cancelRemaining)) {
        // cancelRemainingInstances=true (default): kill active children and leave (Decision 6)
        cancelActiveChildren(scopeExecution);
        leave(scopeExecution);
      } else {
        // cancelRemainingInstances=false: block new triggers and drain (Decision 7)
        scopeExecution.setVariableLocal(COMPLETION_PENDING, true);
        if (active == 0) {
          leave(scopeExecution);
        }
      }
    }
  }

  // -------------------------------------------------------------------------
  // complete — force-complete API entry point (Decision 11)
  // -------------------------------------------------------------------------

  @Override
  public void complete(ActivityExecution scopeExecution) {
    leave(scopeExecution);
  }

  // -------------------------------------------------------------------------
  // doLeave — compensation scope + delegate to super
  // -------------------------------------------------------------------------

  @Override
  public void doLeave(ActivityExecution execution) {
    CompensationUtil.createEventScopeExecution((ExecutionEntity) execution);
    super.doLeave(execution);
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  protected boolean completionConditionSatisfied(ActivityExecution scopeExecution) {
    if (completionConditionExpression == null) {
      return false;
    }
    // Decision 13: evaluated against scope execution variable context only
    Object value = completionConditionExpression.getValue(scopeExecution);
    if (!(value instanceof Boolean)) {
      throw new IllegalStateException(
          "completionCondition expression '" + completionConditionExpression.getExpressionText()
          + "' did not evaluate to a boolean");
    }
    return (Boolean) value;
  }

  protected void cancelActiveChildren(ActivityExecution scopeExecution) {
    List<ActivityExecution> children = new ArrayList<>(
        ((PvmExecutionImpl) scopeExecution).getNonEventScopeExecutions());
    for (ActivityExecution child : children) {
      // delete all not-ended instances; these are either active (for non-scope tasks) or inactive
      // but have no activity id (for scope activities like subprocesses whose execution tree was
      // restructured by createConcurrentExecution, clearing the activity on the scope execution)
      if (child.isActive() || child.getActivity() == null) {
        ((PvmExecutionImpl) child).deleteCascade("Ad-hoc subprocess completion condition satisfied.");
      } else {
        child.remove();
      }
    }
  }

  private int intVar(ActivityExecution execution, String name) {
    Object val = execution.getVariableLocal(name);
    return (val instanceof Number) ? ((Number) val).intValue() : 0;
  }

}
