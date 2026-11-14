package io.vanillabp.cockpit.camunda8.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.UserTask;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListeners;
import io.vanillabp.camunda8.Camunda8ProcessingContext;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.cockpit.camunda8.Camunda8Clients;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitDeployments;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitDeployments.WiredListener;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitListeners;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitWiring;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The cockpit takes Camunda user tasks only, like the Camunda 8 adapter.
 * <p>
 * A user task which a job worker serves has no <code>zeebe:userTask</code>. Camunda deprecated
 * it, and the Camunda 8 adapter ends the boot for such an element in a process the application
 * claims. That holds for an element with a <code>zeebe:taskDefinition</code> of its own as well,
 * because the cluster ignores the job type of a user task and hands out a job of type
 * <code>io.camunda.zeebe:userTask</code> anyway.
 * <p>
 * The cockpit does not refuse such an element a second time. It runs after the adapter, so the
 * adapter's refusal has ended the boot already. What this test makes sure of is the other half:
 * the cockpit never treats such an element as a task of its own. It adds no listener to it,
 * registers nothing for it and tells the cockpit extension nothing about it. So nothing the
 * cockpit does contradicts the adapter's rule, and no listener job waits for a worker which
 * nobody opens.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CockpitJobWorkerUserTasksTest {

  private static final String ADAPTER_ID = "c8";

  private static final String MODULE_ID = "cockpit-module";

  private static final String PROCESS_ID = "CockpitProcess";

  /** The one Camunda user task of the model. */
  private static final String CAMUNDA_USER_TASK = "Approve";

  /** A user task as VanillaBP 1 up to release 1.6.3 served it, with a formKey. */
  private static final String WITH_A_FORM_KEY = "ApproveTheOldWay";

  /** A user task which names a job type of its own. The cluster ignores that job type. */
  private static final String WITH_A_JOB_TYPE_OF_ITS_OWN = "MatchByHand";

  private static final String MODEL = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="%s" isExecutable="true">
          <bpmn:startEvent id="Started" />
          <bpmn:userTask id="%s">
            <bpmn:extensionElements>
              <zeebe:userTask />
              <zeebe:formDefinition externalReference="approve" />
            </bpmn:extensionElements>
          </bpmn:userTask>
          <bpmn:userTask id="%s">
            <bpmn:extensionElements>
              <zeebe:formDefinition formKey="approveTheOldWay" />
            </bpmn:extensionElements>
          </bpmn:userTask>
          <bpmn:userTask id="%s">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="manualMatching" />
            </bpmn:extensionElements>
          </bpmn:userTask>
        </bpmn:process>
      </bpmn:definitions>
      """
      .formatted(PROCESS_ID, CAMUNDA_USER_TASK, WITH_A_FORM_KEY, WITH_A_JOB_TYPE_OF_ITS_OWN);

  private final Camunda8ClientFactoryRegistry clientFactories = mock(
      Camunda8ClientFactoryRegistry.class, RETURNS_DEEP_STUBS);

  private final WorkflowTaskWiring workflowTaskWiring = mock(WorkflowTaskWiring.class);

  private final BusinessCockpitEventPublisher publisher = mock(BusinessCockpitEventPublisher.class);

  private final Camunda8CockpitDeployments deployments = new Camunda8CockpitDeployments();

  private final Camunda8ProcessingContext context = mock(Camunda8ProcessingContext.class);

  /**
   * The model as the Camunda 8 adapter leaves it for the extensions. Without name-clash
   * avoidance the cluster knows the process under the id the application wrote.
   */
  private static BpmnModelInstance aModelVanillaBpWired() {

    final var model = Bpmn
        .readModelFromStream(new ByteArrayInputStream(MODEL.getBytes(StandardCharsets.UTF_8)));
    Camunda8TaskWiring.userTasksOf(model, PROCESS_ID, MODULE_ID, "cockpit-process.bpmn");
    return model;

  }

  /**
   * @return The model after the cockpit wired it as a process the application claims
   */
  private BpmnModelInstance wiredByTheCockpit() {

    when(context.getAdapterId()).thenReturn(ADAPTER_ID);
    when(workflowTaskWiring.isClaimedByAWorkflowService(MODULE_ID, PROCESS_ID)).thenReturn(true);
    when(workflowTaskWiring.resolveWorkflowAggregateIdName(MODULE_ID, PROCESS_ID)).thenReturn("loanId");
    final var wiring = new Camunda8CockpitWiring(
        new Camunda8Clients(clientFactories, null), deployments, null, workflowTaskWiring, () -> publisher, null);

    final var model = aModelVanillaBpWired();
    wiring.wireBpmn(MODULE_ID, "cockpit-process.bpmn", PROCESS_ID, model, context);
    return model;

  }

  private static ZeebeTaskListeners taskListenersOf(
      final BpmnModelInstance model,
      final String elementId) {

    return ((UserTask) model.getModelElementById(elementId))
        .getSingleExtensionElement(ZeebeTaskListeners.class);

  }

  @Test
  @DisplayName("The Camunda 8 adapter finds both job-worker user tasks, and that ends the boot before the cockpit runs")
  public void theAdapterFindsBothJobWorkerUserTasks() {

    final var found = Camunda8TaskWiring.jobWorkerUserTasksOf(aModelVanillaBpWired(), PROCESS_ID);

    assertEquals(List.of(WITH_A_FORM_KEY), found.withVersionOnesFormKey());
    assertEquals(List.of(WITH_A_JOB_TYPE_OF_ITS_OWN), found.withoutAFormKey());

  }

  @Test
  @DisplayName("Only the Camunda user task gets the cockpit's listeners")
  public void onlyTheCamundaUserTaskGetsListeners() {

    final var model = wiredByTheCockpit();

    final var listenerType = Camunda8CockpitListeners.listenerTypeOf("approve");
    assertEquals(
        3,
        taskListenersOf(model, CAMUNDA_USER_TASK)
            .getTaskListeners()
            .stream()
            .filter(listener -> listenerType.equals(listener.getType()))
            .count());
    // the adapter adds no listener to a job-worker user task, and neither does the cockpit
    assertNull(taskListenersOf(model, WITH_A_FORM_KEY));
    assertNull(taskListenersOf(model, WITH_A_JOB_TYPE_OF_ITS_OWN));

  }

  @Test
  @DisplayName("A job-worker user task is neither registered nor told to the cockpit extension")
  public void aJobWorkerUserTaskIsNotReported() {

    wiredByTheCockpit();

    final var userTasksRegistered = deployments
        .listenersByTypeOf(ADAPTER_ID, MODULE_ID)
        .values()
        .stream()
        .flatMap(List::stream)
        .filter(WiredListener::sitsOnAUserTask)
        .map(WiredListener::elementId)
        .toList();
    assertEquals(List.of(CAMUNDA_USER_TASK), userTasksRegistered);
    verify(publisher).userTaskWired(MODULE_ID, PROCESS_ID, "approve", CAMUNDA_USER_TASK);
    verify(publisher, never()).userTaskWired(anyString(), anyString(), anyString(), eq(WITH_A_FORM_KEY));
    verify(publisher, never()).userTaskWired(anyString(), anyString(), anyString(), eq(WITH_A_JOB_TYPE_OF_ITS_OWN));

  }

}
