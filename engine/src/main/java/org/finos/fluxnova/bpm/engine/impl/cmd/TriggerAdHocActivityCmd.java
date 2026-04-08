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
package org.finos.fluxnova.bpm.engine.impl.cmd;

import static org.finos.fluxnova.bpm.engine.impl.util.EnsureUtil.ensureNotNull;

import java.io.Serializable;

import org.finos.fluxnova.bpm.engine.BadUserRequestException;
import org.finos.fluxnova.bpm.engine.impl.bpmn.behavior.AdHocSubProcessActivityBehavior;
import org.finos.fluxnova.bpm.engine.impl.interceptor.Command;
import org.finos.fluxnova.bpm.engine.impl.interceptor.CommandContext;
import org.finos.fluxnova.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.finos.fluxnova.bpm.engine.impl.pvm.delegate.ActivityBehavior;

/**
 * Triggers a named direct-child activity inside an ad-hoc subprocess.
 */
public class TriggerAdHocActivityCmd implements Command<Void>, Serializable {

  private static final long serialVersionUID = 1L;

  protected final String executionId;
  protected final String activityId;

  public TriggerAdHocActivityCmd(String executionId, String activityId) {
    this.executionId = executionId;
    this.activityId  = activityId;
  }

  @Override
  public Void execute(CommandContext commandContext) {
    ensureNotNull(BadUserRequestException.class, "executionId is null", "executionId", executionId);
    ensureNotNull(BadUserRequestException.class, "activityId is null",  "activityId",  activityId);

    ExecutionEntity execution = commandContext.getExecutionManager().findExecutionById(executionId);
    ensureNotNull(BadUserRequestException.class,
        "Execution '" + executionId + "' does not exist", "execution", execution);

    for (org.finos.fluxnova.bpm.engine.impl.cfg.CommandChecker checker :
        commandContext.getProcessEngineConfiguration().getCommandCheckers()) {
      checker.checkUpdateProcessInstance(execution);
    }

    ActivityBehavior behavior = execution.getActivity() != null
        ? execution.getActivity().getActivityBehavior()
        : null;

    if (!(behavior instanceof AdHocSubProcessActivityBehavior)) {
      throw new BadUserRequestException(
          "Execution '" + executionId + "' is not waiting in an ad-hoc subprocess scope");
    }

    ((AdHocSubProcessActivityBehavior) behavior).triggerChildActivity(execution, activityId);
    return null;
  }

}
