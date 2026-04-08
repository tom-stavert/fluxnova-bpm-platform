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
package org.finos.fluxnova.bpm.engine.test.bpmn.subprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.finos.fluxnova.bpm.engine.BadUserRequestException;
import org.finos.fluxnova.bpm.engine.runtime.Execution;
import org.finos.fluxnova.bpm.engine.runtime.ProcessInstance;
import org.finos.fluxnova.bpm.engine.task.Task;
import org.finos.fluxnova.bpm.engine.test.util.PluggableProcessEngineTest;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for {@link org.finos.fluxnova.bpm.engine.impl.bpmn.behavior.AdHocSubProcessActivityBehavior}.
 * Covers all core runtime scenarios.
 *
 * <p>BPMN processes are deployed inline via {@code repositoryService.addString()} so no
 * external resource files are required.
 */
public class AdHocSubProcessBehaviorTest extends PluggableProcessEngineTest {

  private static final String AD_HOC_ID  = "adHoc";
  private static final String PROCESS_KEY = "adHocProcess";

  @Before
  public void deployDefaultProcess() {
    // Deploy on demand per test using helper — no shared deployment here.
  }

  // -------------------------------------------------------------------------
  // 7.3.1 Deploy and start — no children auto-started, counters at 0
  // -------------------------------------------------------------------------

  @Test
  public void testDeployAndStartCountersAtZero() {
    deploy(parallelXml(null, "<userTask id=\"taskA\"/>", "<userTask id=\"taskB\"/>"));

    ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY);

    Execution adHocExec = adHocExecution(pi);
    assertNotNull("ad-hoc scope execution must exist", adHocExec);

    assertThat(nrOfActive(adHocExec)).isEqualTo(0);
    assertThat(nrOfCompleted(adHocExec)).isEqualTo(0);

    // No tasks should be active yet
    assertThat(taskService.createTaskQuery().processInstanceId(pi.getId()).count()).isEqualTo(0);
  }

  // -------------------------------------------------------------------------
  // 7.3.2 triggerAdHocActivity starts child; nrOfActiveInstances = 1
  // -------------------------------------------------------------------------

  @Test
  public void testTriggerActivityStartsChildAndIncrementsCounter() {
    deploy(parallelXml(null, "<userTask id=\"taskA\"/>", "<userTask id=\"taskB\"/>"));

    ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    Execution adHocExec = adHocExecution(pi);

    runtimeService.triggerAdHocActivity(adHocExec.getId(), "taskA");

    assertThat(nrOfActive(adHocExec)).isEqualTo(1);
    assertThat(nrOfCompleted(adHocExec)).isEqualTo(0);

    Task task = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
    assertNotNull(task);
    assertThat(task.getTaskDefinitionKey()).isEqualTo("taskA");
  }

  // -------------------------------------------------------------------------
  // 7.3.3 Complete triggered task; counters update
  // -------------------------------------------------------------------------

  @Test
  public void testCompleteTriggeredTaskUpdatesCounters() {
    deploy(parallelXml(null, "<userTask id=\"taskA\"/>", "<userTask id=\"taskB\"/>"));

    ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    Execution adHocExec = adHocExecution(pi);
    runtimeService.triggerAdHocActivity(adHocExec.getId(), "taskA");

    Task task = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
    taskService.complete(task.getId());

    // Execution id is stable; refresh the variable snapshot
    assertThat(nrOfActive(adHocExec)).isEqualTo(0);
    assertThat(nrOfCompleted(adHocExec)).isEqualTo(1);
  }

  // -------------------------------------------------------------------------
  // 7.3.4 Parallel ordering — two simultaneous children allowed
  // -------------------------------------------------------------------------

  @Test
  public void testParallelAllowsTwoSimultaneousChildren() {
    deploy(parallelXml(null, "<userTask id=\"taskA\"/>", "<userTask id=\"taskB\"/>"));

    ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    Execution adHocExec = adHocExecution(pi);

    runtimeService.triggerAdHocActivity(adHocExec.getId(), "taskA");
    runtimeService.triggerAdHocActivity(adHocExec.getId(), "taskB");

    assertThat(nrOfActive(adHocExec)).isEqualTo(2);

    List<Task> tasks = taskService.createTaskQuery().processInstanceId(pi.getId()).list();
    assertThat(tasks).hasSize(2);
  }

  // -------------------------------------------------------------------------
  // 7.3.5 Sequential ordering — second trigger while first active is rejected
  // -------------------------------------------------------------------------

  @Test
  public void testSequentialRejectsSecondTriggerWhileFirstActive() {
    deploy(sequentialXml(null));

    ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    Execution adHocExec = adHocExecution(pi);

    runtimeService.triggerAdHocActivity(adHocExec.getId(), "taskA");

    assertThatThrownBy(() ->
        runtimeService.triggerAdHocActivity(adHocExec.getId(), "taskB")
    ).isInstanceOf(BadUserRequestException.class)
     .hasMessageContaining("sequential ad-hoc subprocess already has an active child");
  }

  @Test
  public void testSequentialAllowsSecondTriggerAfterFirstCompletes() {
    deploy(sequentialXml(null));

    ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    Execution adHocExec = adHocExecution(pi);

    runtimeService.triggerAdHocActivity(adHocExec.getId(), "taskA");
    taskService.complete(taskService.createTaskQuery().taskDefinitionKey("taskA")
        .processInstanceId(pi.getId()).singleResult().getId());

    // Should not throw
    runtimeService.triggerAdHocActivity(adHocExec.getId(), "taskB");
    assertThat(nrOfActive(adHocExec)).isEqualTo(1);
  }

  // -------------------------------------------------------------------------
  // 7.3.6 Non-existent child activity is rejected
  // -------------------------------------------------------------------------

  @Test
  public void testTriggerNonExistentActivityIsRejected() {
    deploy(parallelXml(null, "<userTask id=\"taskA\"/>"));

    ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    Execution adHocExec = adHocExecution(pi);

    assertThatThrownBy(() ->
        runtimeService.triggerAdHocActivity(adHocExec.getId(), "nonExistent")
    ).isInstanceOf(BadUserRequestException.class)
     .hasMessageContaining("is not a direct child of ad-hoc subprocess");
  }

  // -------------------------------------------------------------------------
  // 7.3.7 Same activity triggered multiple times — each counts independently
  // -------------------------------------------------------------------------

  @Test
  public void testSameActivityTriggeredMultipleTimesCountsIndependently() {
    deploy(parallelXml(null, "<userTask id=\"taskA\"/>"));

    ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    Execution adHocExec = adHocExecution(pi);

    runtimeService.triggerAdHocActivity(adHocExec.getId(), "taskA");
    runtimeService.triggerAdHocActivity(adHocExec.getId(), "taskA");

    assertThat(nrOfActive(adHocExec)).isEqualTo(2);
    assertThat(taskService.createTaskQuery().taskDefinitionKey("taskA")
        .processInstanceId(pi.getId()).count()).isEqualTo(2);
  }

  // -------------------------------------------------------------------------
  // 7.3.8 completionCondition true, cancelRemainingInstances=true → exits
  // -------------------------------------------------------------------------

  @Test
  public void testCompletionConditionSatisfiedCancelsRemainingAndLeaves() {
    // Condition: at least 1 completed. cancelRemainingInstances=true (default).
    deploy(parallelXml("${nrOfCompletedInstances >= 1}",
        "<userTask id=\"taskA\"/>",
        "<userTask id=\"taskB\"/>"));

    ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    Execution adHocExec = adHocExecution(pi);

    runtimeService.triggerAdHocActivity(adHocExec.getId(), "taskA");
    runtimeService.triggerAdHocActivity(adHocExec.getId(), "taskB");

    // Completing taskA satisfies the condition; taskB should be cancelled and process should end
    Task taskA = taskService.createTaskQuery().taskDefinitionKey("taskA")
        .processInstanceId(pi.getId()).singleResult();
    taskService.complete(taskA.getId());

    // Process instance must have ended
    assertNull(runtimeService.createProcessInstanceQuery()
        .processInstanceId(pi.getId()).singleResult());
  }

  // -------------------------------------------------------------------------
  // 7.3.9 completionCondition, cancelRemainingInstances=false → drains, then exits
  // -------------------------------------------------------------------------

  @Test
  public void testCompletionConditionWithCancelRemainingFalseDrainsAndLeaves() {
    deploy(parallelNoCancelXml("${nrOfCompletedInstances >= 1}",
        "<userTask id=\"taskA\"/>",
        "<userTask id=\"taskB\"/>"));

    ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    Execution adHocExec = adHocExecution(pi);

    runtimeService.triggerAdHocActivity(adHocExec.getId(), "taskA");
    runtimeService.triggerAdHocActivity(adHocExec.getId(), "taskB");

    // Complete taskA — condition satisfied, enters drain mode; taskB still active
    Task taskA = taskService.createTaskQuery().taskDefinitionKey("taskA")
        .processInstanceId(pi.getId()).singleResult();
    taskService.complete(taskA.getId());

    // Process should still be running (taskB is draining)
    assertNotNull(runtimeService.createProcessInstanceQuery()
        .processInstanceId(pi.getId()).singleResult());

    // Complete taskB — drain complete, subprocess and process should exit
    Task taskB = taskService.createTaskQuery().taskDefinitionKey("taskB")
        .processInstanceId(pi.getId()).singleResult();
    taskService.complete(taskB.getId());

    assertNull(runtimeService.createProcessInstanceQuery()
        .processInstanceId(pi.getId()).singleResult());
  }

  // -------------------------------------------------------------------------
  // 7.3.10 Trigger while draining (cancelRemainingInstances=false) is rejected
  // -------------------------------------------------------------------------

  @Test
  public void testTriggerWhileDrainingIsRejected() {
    deploy(parallelNoCancelXml("${nrOfCompletedInstances >= 1}",
        "<userTask id=\"taskA\"/>",
        "<userTask id=\"taskB\"/>"));

    ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    Execution adHocExec = adHocExecution(pi);

    runtimeService.triggerAdHocActivity(adHocExec.getId(), "taskA");

    // Complete taskA — condition satisfied, enters drain mode
    Task taskA = taskService.createTaskQuery().taskDefinitionKey("taskA")
        .processInstanceId(pi.getId()).singleResult();
    taskService.complete(taskA.getId());

    // Trying to trigger again must be rejected
    assertThatThrownBy(() ->
        runtimeService.triggerAdHocActivity(adHocExec.getId(), "taskB")
    ).isInstanceOf(BadUserRequestException.class)
     .hasMessageContaining("ad-hoc subprocess is waiting to complete");
  }

  // -------------------------------------------------------------------------
  // 7.3.11 completeAdHocSubprocess force-completes regardless of condition
  // -------------------------------------------------------------------------

  @Test
  public void testForceCompleteExitsSubprocessWithActiveChildren() {
    // No completion condition — requires explicit force-complete
    deploy(parallelXml(null, "<userTask id=\"taskA\"/>", "<userTask id=\"taskB\"/>"));

    ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    Execution adHocExec = adHocExecution(pi);

    runtimeService.triggerAdHocActivity(adHocExec.getId(), "taskA");

    // Force-complete while taskA is active
    runtimeService.completeAdHocSubprocess(adHocExec.getId());

    // Process should have ended
    assertNull(runtimeService.createProcessInstanceQuery()
        .processInstanceId(pi.getId()).singleResult());
  }

  @Test
  public void testForceCompleteWithNoActiveChildrenExitsImmediately() {
    deploy(parallelXml(null, "<userTask id=\"taskA\"/>"));

    ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    Execution adHocExec = adHocExecution(pi);

    // No children triggered — force-complete still works
    runtimeService.completeAdHocSubprocess(adHocExec.getId());

    assertNull(runtimeService.createProcessInstanceQuery()
        .processInstanceId(pi.getId()).singleResult());
  }

  // -------------------------------------------------------------------------
  // 7.3.12 No completionCondition, no force-complete → subprocess stays active
  // -------------------------------------------------------------------------

  @Test
  public void testNoConditionAndNoForceCompleteRemainsActive() {
    deploy(parallelXml(null, "<userTask id=\"taskA\"/>"));

    ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    Execution adHocExec = adHocExecution(pi);

    runtimeService.triggerAdHocActivity(adHocExec.getId(), "taskA");
    Task taskA = taskService.createTaskQuery().taskDefinitionKey("taskA")
        .processInstanceId(pi.getId()).singleResult();
    taskService.complete(taskA.getId());

    // All children completed but no condition / no force-complete → still active
    assertNotNull(runtimeService.createProcessInstanceQuery()
        .processInstanceId(pi.getId()).singleResult());
    assertThat(nrOfActive(adHocExec)).isEqualTo(0);
    assertThat(nrOfCompleted(adHocExec)).isEqualTo(1);
  }

  // -------------------------------------------------------------------------
  // 7.3.13 triggerAdHocActivity on non-ad-hoc execution is rejected
  // -------------------------------------------------------------------------

  @Test
  public void testTriggerOnNonAdHocExecutionIsRejected() {
    // Deploy a simple non-ad-hoc process to get a regular execution id
    testRule.deploy(repositoryService.createDeployment()
        .addString("simple.bpmn",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"http://test\">" +
            "  <process id=\"simpleProcess\" isExecutable=\"true\">" +
            "    <startEvent id=\"start\"><outgoing>flow1</outgoing></startEvent>" +
            "    <sequenceFlow id=\"flow1\" sourceRef=\"start\" targetRef=\"task1\"/>" +
            "    <userTask id=\"task1\"><incoming>flow1</incoming><outgoing>flow2</outgoing></userTask>" +
            "    <sequenceFlow id=\"flow2\" sourceRef=\"task1\" targetRef=\"end\"/>" +
            "    <endEvent id=\"end\"><incoming>flow2</incoming></endEvent>" +
            "  </process>" +
            "</definitions>"));

    ProcessInstance pi = runtimeService.startProcessInstanceByKey("simpleProcess");
    Execution taskExecution = runtimeService.createExecutionQuery()
        .processInstanceId(pi.getId()).activityId("task1").singleResult();

    assertThatThrownBy(() ->
        runtimeService.triggerAdHocActivity(taskExecution.getId(), "task1")
    ).isInstanceOf(BadUserRequestException.class)
     .hasMessageContaining("is not waiting in an ad-hoc subprocess scope");
  }

  @Test
  public void testCompleteAdHocOnNonAdHocExecutionIsRejected() {
    testRule.deploy(repositoryService.createDeployment()
        .addString("simple.bpmn",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"http://test\">" +
            "  <process id=\"simpleProcess2\" isExecutable=\"true\">" +
            "    <startEvent id=\"start\"><outgoing>flow1</outgoing></startEvent>" +
            "    <sequenceFlow id=\"flow1\" sourceRef=\"start\" targetRef=\"task1\"/>" +
            "    <userTask id=\"task1\"><incoming>flow1</incoming><outgoing>flow2</outgoing></userTask>" +
            "    <sequenceFlow id=\"flow2\" sourceRef=\"task1\" targetRef=\"end\"/>" +
            "    <endEvent id=\"end\"><incoming>flow2</incoming></endEvent>" +
            "  </process>" +
            "</definitions>"));

    ProcessInstance pi = runtimeService.startProcessInstanceByKey("simpleProcess2");
    Execution taskExecution = runtimeService.createExecutionQuery()
        .processInstanceId(pi.getId()).activityId("task1").singleResult();

    assertThatThrownBy(() ->
        runtimeService.completeAdHocSubprocess(taskExecution.getId())
    ).isInstanceOf(BadUserRequestException.class)
     .hasMessageContaining("is not waiting in an ad-hoc subprocess scope");
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  /** Find the scope execution parked at the ad-hoc subprocess. */
  private Execution adHocExecution(ProcessInstance pi) {
    return runtimeService.createExecutionQuery()
        .processInstanceId(pi.getId())
        .activityId(AD_HOC_ID)
        .singleResult();
  }

  private int nrOfActive(Execution adHocExec) {
    Object val = runtimeService.getVariableLocal(adHocExec.getId(), "nrOfActiveInstances");
    return val instanceof Number ? ((Number) val).intValue() : 0;
  }

  private int nrOfCompleted(Execution adHocExec) {
    Object val = runtimeService.getVariableLocal(adHocExec.getId(), "nrOfCompletedInstances");
    return val instanceof Number ? ((Number) val).intValue() : 0;
  }

  private void deploy(String bpmnXml) {
    testRule.deploy(repositoryService.createDeployment().addString("adhoc.bpmn", bpmnXml));
  }

  /**
   * Parallel ad-hoc subprocess, cancelRemainingInstances=true.
   */
  private static String parallelXml(String completionCondition, String... childElements) {
    return adHocXml("Parallel", true, completionCondition, childElements);
  }

  /**
   * Sequential ad-hoc subprocess with two user tasks, cancelRemainingInstances=true.
   */
  private static String sequentialXml(String completionCondition) {
    return adHocXml("Sequential", true, completionCondition,
        "<userTask id=\"taskA\"/>",
        "<userTask id=\"taskB\"/>");
  }

  /**
   * Parallel ad-hoc subprocess, cancelRemainingInstances=false.
   */
  private static String parallelNoCancelXml(String completionCondition, String... childElements) {
    return adHocXml("Parallel", false, completionCondition, childElements);
  }

  private static String adHocXml(String ordering, boolean cancelRemaining,
                                  String completionCondition, String... childElements) {
    StringBuilder sb = new StringBuilder();
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
    sb.append("<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"http://test\">");
    sb.append("  <process id=\"adHocProcess\" isExecutable=\"true\">");
    sb.append("    <startEvent id=\"start\"><outgoing>flow1</outgoing></startEvent>");
    sb.append("    <sequenceFlow id=\"flow1\" sourceRef=\"start\" targetRef=\"adHoc\"/>");
    sb.append("    <adHocSubProcess id=\"adHoc\" ordering=\"").append(ordering)
      .append("\" cancelRemainingInstances=\"").append(cancelRemaining).append("\">");
    sb.append("      <incoming>flow1</incoming><outgoing>flow2</outgoing>");
    if (completionCondition != null) {
      sb.append("      <completionCondition>").append(completionCondition).append("</completionCondition>");
    }
    for (String child : childElements) {
      sb.append("      ").append(child);
    }
    sb.append("    </adHocSubProcess>");
    sb.append("    <sequenceFlow id=\"flow2\" sourceRef=\"adHoc\" targetRef=\"end\"/>");
    sb.append("    <endEvent id=\"end\"><incoming>flow2</incoming></endEvent>");
    sb.append("  </process>");
    sb.append("</definitions>");
    return sb.toString();
  }
}
