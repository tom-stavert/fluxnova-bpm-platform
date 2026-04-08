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
package org.finos.fluxnova.bpm.model.bpmn.instance;

import org.finos.fluxnova.bpm.model.bpmn.AdHocOrdering;

import java.util.Arrays;
import java.util.Collection;

import static org.finos.fluxnova.bpm.model.bpmn.impl.BpmnModelConstants.BPMN_ATTRIBUTE_CANCEL_REMAINING_INSTANCES;
import static org.finos.fluxnova.bpm.model.bpmn.impl.BpmnModelConstants.BPMN_ATTRIBUTE_ORDERING;

/**
 * Unit tests for the {@link AdHocSubProcess} model-API element:
 * verifies type hierarchy, attributes, and child elements registered
 * by {@link org.finos.fluxnova.bpm.model.bpmn.impl.instance.AdHocSubProcessImpl}.
 */
public class AdHocSubProcessTest extends BpmnModelElementInstanceTest {

  @Override
  public TypeAssumption getTypeAssumption() {
    // adHocSubProcess extends subProcess, is not abstract
    return new TypeAssumption(SubProcess.class, false);
  }

  @Override
  public Collection<ChildElementAssumption> getChildElementAssumptions() {
    // completionCondition is optional (0..1)
    return Arrays.asList(
      new ChildElementAssumption(CompletionCondition.class, 0, 1)
    );
  }

  @Override
  public Collection<AttributeAssumption> getAttributesAssumptions() {
    return Arrays.asList(
      // ordering: not an ID attribute, not required, default Parallel
      new AttributeAssumption(BPMN_ATTRIBUTE_ORDERING, false, false, AdHocOrdering.Parallel),
      // cancelRemainingInstances: not an ID attribute, not required, default true
      new AttributeAssumption(BPMN_ATTRIBUTE_CANCEL_REMAINING_INSTANCES, false, false, true)
    );
  }
}
