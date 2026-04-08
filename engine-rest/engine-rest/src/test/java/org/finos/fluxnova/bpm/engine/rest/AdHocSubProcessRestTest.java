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
package org.finos.fluxnova.bpm.engine.rest;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response.Status;

import org.finos.fluxnova.bpm.engine.AuthorizationException;
import org.finos.fluxnova.bpm.engine.BadUserRequestException;
import org.finos.fluxnova.bpm.engine.ProcessEngineException;
import org.finos.fluxnova.bpm.engine.impl.RuntimeServiceImpl;
import org.finos.fluxnova.bpm.engine.rest.exception.InvalidRequestException;
import org.finos.fluxnova.bpm.engine.rest.exception.RestException;
import org.finos.fluxnova.bpm.engine.rest.helper.MockProvider;
import org.finos.fluxnova.bpm.engine.rest.util.container.TestContainerRule;
import org.hamcrest.CoreMatchers;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import io.restassured.http.ContentType;

/**
 * Integration tests for the ad-hoc subprocess REST endpoints:
 * <ul>
 *   <li>{@code POST /execution/{id}/trigger-ad-hoc-activity}</li>
 *   <li>{@code POST /execution/{id}/complete-ad-hoc}</li>
 * </ul>
 *
 * Uses the same mock-based approach as {@link ExecutionRestServiceInteractionTest}.
 */
public class AdHocSubProcessRestTest extends AbstractRestServiceTest {

  @ClassRule
  public static TestContainerRule rule = new TestContainerRule();

  protected static final String EXECUTION_URL =
      TEST_RESOURCE_ROOT_PATH + "/execution/{id}";
  protected static final String TRIGGER_AD_HOC_URL =
      EXECUTION_URL + "/trigger-ad-hoc-activity";
  protected static final String COMPLETE_AD_HOC_URL =
      EXECUTION_URL + "/complete-ad-hoc";

  private RuntimeServiceImpl runtimeServiceMock;

  @Before
  public void setUpRuntimeData() {
    runtimeServiceMock = mock(RuntimeServiceImpl.class);
    when(processEngine.getRuntimeService()).thenReturn(runtimeServiceMock);
  }

  // -------------------------------------------------------------------------
  // POST /execution/{id}/trigger-ad-hoc-activity
  // -------------------------------------------------------------------------

  @Test
  public void testTriggerAdHocActivityReturns204() {
    doNothing().when(runtimeServiceMock)
        .triggerAdHocActivity(MockProvider.EXAMPLE_EXECUTION_ID, MockProvider.EXAMPLE_ACTIVITY_ID);

    given()
        .pathParam("id", MockProvider.EXAMPLE_EXECUTION_ID)
        .contentType(ContentType.JSON)
        .body("{\"activityId\":\"" + MockProvider.EXAMPLE_ACTIVITY_ID + "\"}")
    .then().expect()
        .statusCode(Status.NO_CONTENT.getStatusCode())
    .when().post(TRIGGER_AD_HOC_URL);

    verify(runtimeServiceMock)
        .triggerAdHocActivity(MockProvider.EXAMPLE_EXECUTION_ID, MockProvider.EXAMPLE_ACTIVITY_ID);
  }

  @Test
  public void testTriggerAdHocActivityWithInvalidActivityIdReturns400() {
    doThrow(new BadUserRequestException("Activity 'unknown' is not a direct child"))
        .when(runtimeServiceMock)
        .triggerAdHocActivity(MockProvider.EXAMPLE_EXECUTION_ID, "unknown");

    given()
        .pathParam("id", MockProvider.EXAMPLE_EXECUTION_ID)
        .contentType(ContentType.JSON)
        .body("{\"activityId\":\"unknown\"}")
    .then().expect()
        .statusCode(Status.BAD_REQUEST.getStatusCode())
        .contentType(ContentType.JSON)
        .body("type", CoreMatchers.equalTo(InvalidRequestException.class.getSimpleName()))
        .body("message", CoreMatchers.containsString("Activity 'unknown' is not a direct child"))
    .when().post(TRIGGER_AD_HOC_URL);
  }

  @Test
  public void testTriggerAdHocActivityOnNonAdHocExecutionReturns400() {
    doThrow(new BadUserRequestException("Execution 'anExecutionId' is not waiting in an ad-hoc subprocess scope"))
        .when(runtimeServiceMock)
        .triggerAdHocActivity(MockProvider.EXAMPLE_EXECUTION_ID, MockProvider.EXAMPLE_ACTIVITY_ID);

    given()
        .pathParam("id", MockProvider.EXAMPLE_EXECUTION_ID)
        .contentType(ContentType.JSON)
        .body("{\"activityId\":\"" + MockProvider.EXAMPLE_ACTIVITY_ID + "\"}")
    .then().expect()
        .statusCode(Status.BAD_REQUEST.getStatusCode())
        .contentType(ContentType.JSON)
        .body("type", CoreMatchers.equalTo(InvalidRequestException.class.getSimpleName()))
    .when().post(TRIGGER_AD_HOC_URL);
  }

  @Test
  public void testTriggerAdHocActivitySequentialConflictReturns400() {
    doThrow(new BadUserRequestException("sequential ad-hoc subprocess already has an active child"))
        .when(runtimeServiceMock)
        .triggerAdHocActivity(MockProvider.EXAMPLE_EXECUTION_ID, MockProvider.EXAMPLE_ACTIVITY_ID);

    given()
        .pathParam("id", MockProvider.EXAMPLE_EXECUTION_ID)
        .contentType(ContentType.JSON)
        .body("{\"activityId\":\"" + MockProvider.EXAMPLE_ACTIVITY_ID + "\"}")
    .then().expect()
        .statusCode(Status.BAD_REQUEST.getStatusCode())
        .body("message", CoreMatchers.containsString("sequential ad-hoc subprocess already has an active child"))
    .when().post(TRIGGER_AD_HOC_URL);
  }

  @Test
  public void testTriggerAdHocActivityWhileDrainingReturns400() {
    doThrow(new BadUserRequestException("ad-hoc subprocess is waiting to complete"))
        .when(runtimeServiceMock)
        .triggerAdHocActivity(MockProvider.EXAMPLE_EXECUTION_ID, MockProvider.EXAMPLE_ACTIVITY_ID);

    given()
        .pathParam("id", MockProvider.EXAMPLE_EXECUTION_ID)
        .contentType(ContentType.JSON)
        .body("{\"activityId\":\"" + MockProvider.EXAMPLE_ACTIVITY_ID + "\"}")
    .then().expect()
        .statusCode(Status.BAD_REQUEST.getStatusCode())
        .body("message", CoreMatchers.containsString("ad-hoc subprocess is waiting to complete"))
    .when().post(TRIGGER_AD_HOC_URL);
  }

  @Test
  public void testTriggerAdHocActivityUnauthorizedReturns403() {
    doThrow(new AuthorizationException("user", "UPDATE", "process-instance", MockProvider.EXAMPLE_EXECUTION_ID))
        .when(runtimeServiceMock)
        .triggerAdHocActivity(MockProvider.EXAMPLE_EXECUTION_ID, MockProvider.EXAMPLE_ACTIVITY_ID);

    given()
        .pathParam("id", MockProvider.EXAMPLE_EXECUTION_ID)
        .contentType(ContentType.JSON)
        .body("{\"activityId\":\"" + MockProvider.EXAMPLE_ACTIVITY_ID + "\"}")
    .then().expect()
        .statusCode(Status.FORBIDDEN.getStatusCode())
    .when().post(TRIGGER_AD_HOC_URL);
  }

  @Test
  public void testTriggerAdHocActivityEngineExceptionReturns500() {
    doThrow(new ProcessEngineException("unexpected engine error"))
        .when(runtimeServiceMock)
        .triggerAdHocActivity(MockProvider.EXAMPLE_EXECUTION_ID, MockProvider.EXAMPLE_ACTIVITY_ID);

    given()
        .pathParam("id", MockProvider.EXAMPLE_EXECUTION_ID)
        .contentType(ContentType.JSON)
        .body("{\"activityId\":\"" + MockProvider.EXAMPLE_ACTIVITY_ID + "\"}")
    .then().expect()
        .statusCode(Status.INTERNAL_SERVER_ERROR.getStatusCode())
        .body("type", CoreMatchers.equalTo(RestException.class.getSimpleName()))
    .when().post(TRIGGER_AD_HOC_URL);
  }

  // -------------------------------------------------------------------------
  // POST /execution/{id}/complete-ad-hoc
  // -------------------------------------------------------------------------

  @Test
  public void testCompleteAdHocReturns204() {
    doNothing().when(runtimeServiceMock)
        .completeAdHocSubprocess(MockProvider.EXAMPLE_EXECUTION_ID);

    given()
        .pathParam("id", MockProvider.EXAMPLE_EXECUTION_ID)
    .then().expect()
        .statusCode(Status.NO_CONTENT.getStatusCode())
    .when().post(COMPLETE_AD_HOC_URL);

    verify(runtimeServiceMock).completeAdHocSubprocess(MockProvider.EXAMPLE_EXECUTION_ID);
  }

  @Test
  public void testCompleteAdHocOnNonAdHocExecutionReturns400() {
    doThrow(new BadUserRequestException("Execution 'anExecutionId' is not waiting in an ad-hoc subprocess scope"))
        .when(runtimeServiceMock)
        .completeAdHocSubprocess(MockProvider.EXAMPLE_EXECUTION_ID);

    given()
        .pathParam("id", MockProvider.EXAMPLE_EXECUTION_ID)
    .then().expect()
        .statusCode(Status.BAD_REQUEST.getStatusCode())
        .contentType(ContentType.JSON)
        .body("type", CoreMatchers.equalTo(InvalidRequestException.class.getSimpleName()))
    .when().post(COMPLETE_AD_HOC_URL);
  }

  @Test
  public void testCompleteAdHocUnauthorizedReturns403() {
    doThrow(new AuthorizationException("user", "UPDATE", "process-instance", MockProvider.EXAMPLE_EXECUTION_ID))
        .when(runtimeServiceMock)
        .completeAdHocSubprocess(MockProvider.EXAMPLE_EXECUTION_ID);

    given()
        .pathParam("id", MockProvider.EXAMPLE_EXECUTION_ID)
    .then().expect()
        .statusCode(Status.FORBIDDEN.getStatusCode())
    .when().post(COMPLETE_AD_HOC_URL);
  }

  @Test
  public void testCompleteAdHocEngineExceptionReturns500() {
    doThrow(new ProcessEngineException("unexpected engine error"))
        .when(runtimeServiceMock)
        .completeAdHocSubprocess(MockProvider.EXAMPLE_EXECUTION_ID);

    given()
        .pathParam("id", MockProvider.EXAMPLE_EXECUTION_ID)
    .then().expect()
        .statusCode(Status.INTERNAL_SERVER_ERROR.getStatusCode())
        .body("type", CoreMatchers.equalTo(RestException.class.getSimpleName()))
    .when().post(COMPLETE_AD_HOC_URL);
  }
}
