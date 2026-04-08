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
package org.finos.fluxnova.bpm.engine.test.bpmn.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.CoreMatchers.containsString;

import org.finos.fluxnova.bpm.engine.ParseException;
import org.finos.fluxnova.bpm.engine.RepositoryService;
import org.finos.fluxnova.bpm.engine.RuntimeService;
import org.finos.fluxnova.bpm.engine.impl.bpmn.behavior.AdHocSubProcessActivityBehavior;
import org.finos.fluxnova.bpm.engine.impl.bpmn.helper.BpmnProperties;
import org.finos.fluxnova.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.finos.fluxnova.bpm.engine.impl.pvm.process.ActivityImpl;
import org.finos.fluxnova.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.finos.fluxnova.bpm.model.bpmn.AdHocOrdering;
import org.finos.fluxnova.bpm.engine.repository.DeploymentWithDefinitions;
import org.finos.fluxnova.bpm.engine.repository.ProcessDefinition;
import org.finos.fluxnova.bpm.engine.test.ProcessEngineRule;
import org.finos.fluxnova.bpm.engine.test.util.ProcessEngineTestRule;
import org.finos.fluxnova.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.finos.fluxnova.commons.testing.ProcessEngineLoggingRule;
import org.finos.fluxnova.commons.testing.WatchLogger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

/**
 * Parser-level tests for BPMN ad-hoc subprocess (Step 7.2).
 *
 * Verifies that {@code parseAdHocSubProcess} in {@link org.finos.fluxnova.bpm.engine.impl.bpmn.parser.BpmnParse}:
 * <ul>
 *   <li>assigns {@link AdHocSubProcessActivityBehavior}</li>
 *   <li>stores ordering and cancelRemainingInstances in activity properties</li>
 *   <li>wires the completionCondition expression into the behavior</li>
 *   <li>rejects start events and end events as direct children</li>
 *   <li>warns when no directly-triggerable activities exist</li>
 *   <li>warns (but succeeds) when the subprocess has zero children</li>
 * </ul>
 */
public class AdHocSubProcessParseTest {

  private static final String LOGGER = "org.finos.fluxnova.bpm.engine.impl.bpmn.parser.BpmnParse";

  public ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  public ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  @Rule
  public RuleChain chain = RuleChain.outerRule(engineRule).around(testRule);

  @Rule
  public ProcessEngineLoggingRule loggingRule = new ProcessEngineLoggingRule();

  private RepositoryService repositoryService;
  private RuntimeService runtimeService;
  private ProcessEngineConfigurationImpl processEngineConfiguration;

  @Before
  public void setup() {
    repositoryService = engineRule.getRepositoryService();
    runtimeService = engineRule.getRuntimeService();
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();
  }

  @After
  public void tearDown() {
    for (org.finos.fluxnova.bpm.engine.repository.Deployment d : repositoryService.createDeploymentQuery().list()) {
      repositoryService.deleteDeployment(d.getId(), true);
    }
  }

  // -------------------------------------------------------------------------
  // 7.2.1 Successful deployment — behavior assigned
  // -------------------------------------------------------------------------

  @Test
  public void testDeployAssignsAdHocBehavior() {
    DeploymentWithDefinitions deployment = testRule.deploy(
        repositoryService.createDeployment().addString("adhoc.bpmn", adHocXml("Parallel", true, null,
            "<userTask id=\"taskA\"/>")));

    ProcessDefinition pd = deployment.getDeployedProcessDefinitions().get(0);
    ActivityImpl adHocActivity = getActivityImpl(pd, "adHoc");

    assertThat(adHocActivity.getActivityBehavior()).isInstanceOf(AdHocSubProcessActivityBehavior.class);
  }

  // -------------------------------------------------------------------------
  // 7.2.2 Ordering property stored
  // -------------------------------------------------------------------------

  @Test
  public void testSequentialOrderingStoredInProperties() {
    DeploymentWithDefinitions deployment = testRule.deploy(
        repositoryService.createDeployment().addString("adhoc.bpmn", adHocXml("Sequential", true, null,
            "<userTask id=\"taskA\"/>")));

    ActivityImpl adHocActivity = getActivityImpl(deployment.getDeployedProcessDefinitions().get(0), "adHoc");
    AdHocOrdering ordering = adHocActivity.getProperties().get(BpmnProperties.AD_HOC_ORDERING);

    assertThat(ordering).isEqualTo(AdHocOrdering.Sequential);
  }

  @Test
  public void testParallelOrderingStoredInProperties() {
    DeploymentWithDefinitions deployment = testRule.deploy(
        repositoryService.createDeployment().addString("adhoc.bpmn", adHocXml("Parallel", true, null,
            "<userTask id=\"taskA\"/>")));

    ActivityImpl adHocActivity = getActivityImpl(deployment.getDeployedProcessDefinitions().get(0), "adHoc");
    AdHocOrdering ordering = adHocActivity.getProperties().get(BpmnProperties.AD_HOC_ORDERING);

    assertThat(ordering).isEqualTo(AdHocOrdering.Parallel);
  }

  // -------------------------------------------------------------------------
  // 7.2.3 cancelRemainingInstances property stored
  // -------------------------------------------------------------------------

  @Test
  public void testCancelRemainingInstancesFalseStoredInProperties() {
    DeploymentWithDefinitions deployment = testRule.deploy(
        repositoryService.createDeployment().addString("adhoc.bpmn", adHocXml("Parallel", false, null,
            "<userTask id=\"taskA\"/>")));

    ActivityImpl adHocActivity = getActivityImpl(deployment.getDeployedProcessDefinitions().get(0), "adHoc");
    Boolean cancelRemaining = adHocActivity.getProperties().get(BpmnProperties.AD_HOC_CANCEL_REMAINING_INSTANCES);

    assertThat(cancelRemaining).isFalse();
  }

  @Test
  public void testCancelRemainingInstancesDefaultIsTrue() {
    // Omit the attribute entirely — defaults to true per BPMN 2.0 spec
    String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"http://test\">" +
        "  <process id=\"adHocProcess\" isExecutable=\"true\">" +
        "    <startEvent id=\"start\"><outgoing>flow1</outgoing></startEvent>" +
        "    <sequenceFlow id=\"flow1\" sourceRef=\"start\" targetRef=\"adHoc\"/>" +
        "    <adHocSubProcess id=\"adHoc\">" +
        "      <incoming>flow1</incoming><outgoing>flow2</outgoing>" +
        "      <userTask id=\"taskA\"/>" +
        "    </adHocSubProcess>" +
        "    <sequenceFlow id=\"flow2\" sourceRef=\"adHoc\" targetRef=\"end\"/>" +
        "    <endEvent id=\"end\"><incoming>flow2</incoming></endEvent>" +
        "  </process>" +
        "</definitions>";

    DeploymentWithDefinitions deployment = testRule.deploy(
        repositoryService.createDeployment().addString("adhoc.bpmn", xml));

    ActivityImpl adHocActivity = getActivityImpl(deployment.getDeployedProcessDefinitions().get(0), "adHoc");
    Boolean cancelRemaining = adHocActivity.getProperties().get(BpmnProperties.AD_HOC_CANCEL_REMAINING_INSTANCES);

    assertThat(cancelRemaining).isTrue();
  }

  // -------------------------------------------------------------------------
  // 7.2.4 completionCondition expression wired into behavior
  // -------------------------------------------------------------------------

  @Test
  public void testCompletionConditionWiredIntoBehavior() {
    DeploymentWithDefinitions deployment = testRule.deploy(
        repositoryService.createDeployment().addString("adhoc.bpmn",
            adHocXml("Parallel", true, "${nrOfCompletedInstances >= 1}", "<userTask id=\"taskA\"/>")));

    ActivityImpl adHocActivity = getActivityImpl(deployment.getDeployedProcessDefinitions().get(0), "adHoc");
    AdHocSubProcessActivityBehavior behavior =
        (AdHocSubProcessActivityBehavior) adHocActivity.getActivityBehavior();

    assertThat(behavior).isNotNull();
    // The expression is wired — confirmed by the getter returning non-null.
    // Functional verification is covered in AdHocSubProcessBehaviorTest.
    assertThat(behavior.getCompletionConditionExpression()).isNotNull();
    assertThat(behavior.getCompletionConditionExpression().getExpressionText())
        .isEqualTo("${nrOfCompletedInstances >= 1}");
  }

  // -------------------------------------------------------------------------
  // 7.2.5 Parse error: startEvent inside ad-hoc subprocess
  // -------------------------------------------------------------------------

  @Test
  public void testStartEventInsideAdHocIsParseError() {
    String xml = adHocXmlWithStartEvent();

    assertThatThrownBy(() ->
        repositoryService.createDeployment().addString("adhoc.bpmn", xml).deploy()
    ).isInstanceOf(ParseException.class)
     .hasMessageContaining("An ad-hoc subprocess must not contain a startEvent");
  }

  // -------------------------------------------------------------------------
  // 7.2.6 Parse error: endEvent inside ad-hoc subprocess
  // -------------------------------------------------------------------------

  @Test
  public void testEndEventInsideAdHocIsParseError() {
    String xml = adHocXmlWithEndEvent();

    assertThatThrownBy(() ->
        repositoryService.createDeployment().addString("adhoc.bpmn", xml).deploy()
    ).isInstanceOf(ParseException.class)
     .hasMessageContaining("An ad-hoc subprocess must not contain an endEvent");
  }

  // -------------------------------------------------------------------------
  // 7.2.7 Warning: no directly-triggerable activities (all have incoming flows)
  // -------------------------------------------------------------------------

  @Test
  @WatchLogger(loggerNames = LOGGER, level = "WARN")
  public void testNoTriggerableActivitiesProducesWarning() {
    // Both tasks have incoming flows — neither is directly triggerable
    String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"http://test\">" +
        "  <process id=\"adHocProcess\" isExecutable=\"true\">" +
        "    <startEvent id=\"start\"><outgoing>flow1</outgoing></startEvent>" +
        "    <sequenceFlow id=\"flow1\" sourceRef=\"start\" targetRef=\"adHoc\"/>" +
        "    <adHocSubProcess id=\"adHoc\">" +
        "      <incoming>flow1</incoming><outgoing>flow3</outgoing>" +
        "      <userTask id=\"taskA\"><outgoing>flowAB</outgoing></userTask>" +
        "      <userTask id=\"taskB\"><incoming>flowAB</incoming></userTask>" +
        "      <sequenceFlow id=\"flowAB\" sourceRef=\"taskA\" targetRef=\"taskB\"/>" +
        "    </adHocSubProcess>" +
        "    <sequenceFlow id=\"flow3\" sourceRef=\"adHoc\" targetRef=\"end\"/>" +
        "    <endEvent id=\"end\"><incoming>flow3</incoming></endEvent>" +
        "  </process>" +
        "</definitions>";

    // Deployment must succeed despite the warning
    testRule.deploy(repositoryService.createDeployment().addString("adhoc.bpmn", xml));

    assertThat(loggingRule.getFilteredLog("no directly-triggerable activities")).isNotEmpty();
  }

  // -------------------------------------------------------------------------
  // 7.2.8 Warning: zero children
  // -------------------------------------------------------------------------

  @Test
  @WatchLogger(loggerNames = LOGGER, level = "WARN")
  public void testZeroChildrenProducesWarningButDeploySucceeds() {
    String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"http://test\">" +
        "  <process id=\"adHocProcess\" isExecutable=\"true\">" +
        "    <startEvent id=\"start\"><outgoing>flow1</outgoing></startEvent>" +
        "    <sequenceFlow id=\"flow1\" sourceRef=\"start\" targetRef=\"adHoc\"/>" +
        "    <adHocSubProcess id=\"adHoc\">" +
        "      <incoming>flow1</incoming><outgoing>flow2</outgoing>" +
        "    </adHocSubProcess>" +
        "    <sequenceFlow id=\"flow2\" sourceRef=\"adHoc\" targetRef=\"end\"/>" +
        "    <endEvent id=\"end\"><incoming>flow2</incoming></endEvent>" +
        "  </process>" +
        "</definitions>";

    // Deployment must succeed
    testRule.deploy(repositoryService.createDeployment().addString("adhoc.bpmn", xml));

    assertThat(loggingRule.getFilteredLog("no directly-triggerable activities")).isNotEmpty();
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private ActivityImpl getActivityImpl(ProcessDefinition pd, String activityId) {
    ProcessDefinitionEntity entity = processEngineConfiguration.getDeploymentCache()
        .getProcessDefinitionCache().get(pd.getId());
    return entity.findActivity(activityId);
  }

  /**
   * Builds a minimal ad-hoc subprocess BPMN XML string.
   *
   * @param ordering             "Parallel" or "Sequential"
   * @param cancelRemaining      value for cancelRemainingInstances attribute
   * @param completionCondition  expression text, or {@code null} to omit the element
   * @param childElements        raw XML snippets for direct child elements of the adHocSubProcess
   */
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

  private static String adHocXmlWithStartEvent() {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"http://test\">" +
        "  <process id=\"adHocProcess\" isExecutable=\"true\">" +
        "    <startEvent id=\"start\"><outgoing>flow1</outgoing></startEvent>" +
        "    <sequenceFlow id=\"flow1\" sourceRef=\"start\" targetRef=\"adHoc\"/>" +
        "    <adHocSubProcess id=\"adHoc\">" +
        "      <incoming>flow1</incoming><outgoing>flow2</outgoing>" +
        "      <startEvent id=\"innerStart\"/>" +
        "      <userTask id=\"taskA\"/>" +
        "    </adHocSubProcess>" +
        "    <sequenceFlow id=\"flow2\" sourceRef=\"adHoc\" targetRef=\"end\"/>" +
        "    <endEvent id=\"end\"><incoming>flow2</incoming></endEvent>" +
        "  </process>" +
        "</definitions>";
  }

  private static String adHocXmlWithEndEvent() {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"http://test\">" +
        "  <process id=\"adHocProcess\" isExecutable=\"true\">" +
        "    <startEvent id=\"start\"><outgoing>flow1</outgoing></startEvent>" +
        "    <sequenceFlow id=\"flow1\" sourceRef=\"start\" targetRef=\"adHoc\"/>" +
        "    <adHocSubProcess id=\"adHoc\">" +
        "      <incoming>flow1</incoming><outgoing>flow2</outgoing>" +
        "      <userTask id=\"taskA\"/>" +
        "      <endEvent id=\"innerEnd\"/>" +
        "    </adHocSubProcess>" +
        "    <sequenceFlow id=\"flow2\" sourceRef=\"adHoc\" targetRef=\"end\"/>" +
        "    <endEvent id=\"end\"><incoming>flow2</incoming></endEvent>" +
        "  </process>" +
        "</definitions>";
  }
}
