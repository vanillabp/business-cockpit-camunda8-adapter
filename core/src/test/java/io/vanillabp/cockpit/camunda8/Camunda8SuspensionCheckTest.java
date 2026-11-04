package io.vanillabp.cockpit.camunda8;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitDeployments.WiredListener;
import io.vanillabp.cockpit.camunda8.Camunda8SuspensionLookups.Instance;
import io.vanillabp.cockpit.camunda8.Camunda8SuspensionLookups.OpenUserTask;
import io.vanillabp.cockpit.camunda8.Camunda8SuspensionLookups.State;
import io.vanillabp.cockpit.camunda8.Camunda8SuspensionLookups.SuspensionChange;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.cockpit.extension.spi.EventTransaction;
import io.vanillabp.cockpit.extension.spi.UserTaskEventKind;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowEventKind;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the check of suspended workflows reports, and what it asks the cluster for, without a
 * cluster. The questions are answered by a double, and the test counts them. A real cluster
 * answers them in the integration test of the 8.10 line.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8SuspensionCheckTest {

  private static final String ADAPTER_ID = "c8";

  private static final String MODULE_ID = "cockpit-module";

  private static final String PROCESS_ID = "CockpitProcess";

  private static final String AGGREGATE_ID_NAME = "loanId";

  private static final long INSTANCE = 2251799813685331L;

  private static final long TASK = 2251799813685350L;

  private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 2, 12, 0, 0, 0, ZoneOffset.UTC);

  private static final OffsetDateTime SUSPENDED_AT = NOW.minusMinutes(20);

  private final Camunda8Clients clients = new Camunda8Clients(
      mock(Camunda8ClientFactoryRegistry.class, RETURNS_DEEP_STUBS), null);

  private final Camunda8CockpitDeployments deployments = new Camunda8CockpitDeployments();

  private final BusinessCockpitEventPublisher publisher = mock(BusinessCockpitEventPublisher.class);

  private final Cluster cluster = new Cluster();

  private Duration interval = Duration.ofHours(1);

  private Camunda8SuspensionCheck check;

  /**
   * The cluster of this test. It answers what a test put into it, and counts the questions.
   */
  private static class Cluster implements Camunda8SuspensionLookups {

    boolean canSuspend = true;

    final Map<Long, Instance> suspended = new LinkedHashMap<>();

    final Map<Long, Instance> all = new LinkedHashMap<>();

    final List<OpenUserTask> tasks = new ArrayList<>();

    final Map<Long, Map<String, String>> variables = new LinkedHashMap<>();

    final Map<Long, SuspensionChange> auditLog = new LinkedHashMap<>();

    final List<Long> resumedWhileDown = new ArrayList<>();

    final List<String> questions = new ArrayList<>();

    RuntimeException failure;

    RuntimeException auditLogFailure;

    @Override
    public boolean theClusterCanSuspend() {

      return canSuspend;

    }

    @Override
    public Map<Long, Instance> suspendedInstancesOf(
        final String tenantId,
        final Collection<String> scopedBpmnProcessIds) {

      questions.add("suspended "
          + scopedBpmnProcessIds);
      if (failure != null) {
        throw failure;
      }
      return new LinkedHashMap<>(suspended);

    }

    @Override
    public Map<Long, Instance> instancesOf(
        final String tenantId,
        final Collection<Long> processInstanceKeys) {

      questions.add("instances "
          + processInstanceKeys);
      final var found = new LinkedHashMap<Long, Instance>();
      processInstanceKeys.stream().filter(all::containsKey).forEach(key -> found.put(key, all.get(key)));
      return found;

    }

    @Override
    public List<OpenUserTask> openUserTasksOf(
        final String tenantId,
        final Collection<Long> processInstanceKeys) {

      questions.add("tasks "
          + processInstanceKeys);
      return tasks.stream().filter(task -> processInstanceKeys.contains(task.processInstanceKey())).toList();

    }

    @Override
    public Map<Long, Map<String, String>> variablesOf(
        final String tenantId,
        final Collection<Long> processInstanceKeys,
        final Collection<String> names) {

      questions.add("variables "
          + processInstanceKeys);
      return variables;

    }

    @Override
    public Map<Long, SuspensionChange> lastSuspensionChangesOf(
        final String tenantId,
        final Collection<Long> processInstanceKeys) {

      questions.add("audit log "
          + processInstanceKeys);
      return auditLog;

    }

    @Override
    public List<Long> resumedSince(
        final String tenantId,
        final Collection<String> scopedBpmnProcessIds,
        final OffsetDateTime since) {

      questions.add("resumed since "
          + since);
      return resumedWhileDown;

    }

  }

  @BeforeEach
  public void aWiredModule() {

    deployments
        .register(
            ADAPTER_ID, MODULE_ID,
            new WiredListener(
                "listener", PROCESS_ID, PROCESS_ID, PROCESS_ID, "Cockpit process", "Cockpit process", AGGREGATE_ID_NAME));
    check = new Camunda8SuspensionCheck(
        clients, deployments, new Camunda8CockpitSettings() {

          @Override
          public Duration listenerJobTimeout(
              final String workflowModuleId,
              final String adapterId) {

            return Duration.ofMinutes(5);

          }

          @Override
          public Duration suspensionCheckInterval(
              final String adapterId) {

            return interval;

          }

        }, () -> publisher, adapterId -> cluster, () -> NOW);
    cluster.variables.put(INSTANCE, Map.of(AGGREGATE_ID_NAME, "4711"));
    cluster.tasks.add(new OpenUserTask(TASK, INSTANCE, null, PROCESS_ID, 3, "Approve", "approve"));

  }

  @AfterEach
  public void stopTheCheck() {

    check.close();

  }

  private static Instance suspended(
      final long key,
      final OffsetDateTime at) {

    return new Instance(key, PROCESS_ID, 3, null, null, State.SUSPENDED, at);

  }

  private static Instance withState(
      final long key,
      final State state) {

    return new Instance(key, PROCESS_ID, 3, null, null, state, null);

  }

  private void aStartedModule() {

    check.moduleStarted(ADAPTER_ID, MODULE_ID);

  }

  @Test
  @DisplayName("A suspended instance is reported as a suspended workflow, with its task, the moment of the cluster and the person of the audit log")
  public void aSuspendedInstanceIsReportedWithItsTask() {

    cluster.suspended.put(INSTANCE, suspended(INSTANCE, SUSPENDED_AT));
    cluster.auditLog.put(INSTANCE, new SuspensionChange(INSTANCE, true, SUSPENDED_AT, "demo"));
    aStartedModule();

    check.check(ADAPTER_ID);

    final var workflow = ArgumentCaptor.forClass(WorkflowReference.class);
    verify(publisher)
        .publishWorkflowSuspension(
            workflow.capture(), eq(WorkflowEventKind.SUSPENDED), eq("suspended-%d-%d"
                .formatted(INSTANCE, SUSPENDED_AT.toInstant().toEpochMilli())),
            eq(SUSPENDED_AT), eq("demo"),
            eq(EventTransaction.NEW));
    assertEquals(
        new WorkflowReference(ADAPTER_ID, MODULE_ID, PROCESS_ID, "3", "4711", String.valueOf(INSTANCE)),
        workflow.getValue());
    final var task = ArgumentCaptor.forClass(UserTaskReference.class);
    verify(publisher)
        .publishUserTaskSuspension(
            task.capture(), eq(UserTaskEventKind.SUSPENDED), any(), eq(SUSPENDED_AT), eq("demo"),
            eq(EventTransaction.NEW));
    assertEquals(
        new UserTaskReference(
            ADAPTER_ID, MODULE_ID, PROCESS_ID, "3", "4711", String.valueOf(INSTANCE), String
                .valueOf(TASK), "approve", "Approve"),
        task.getValue());

  }

  @Test
  @DisplayName("A run in which nothing changed asks the cluster one question and reports nothing")
  public void aRunWithoutChangesAsksOneQuestion() {

    cluster.suspended.put(INSTANCE, suspended(INSTANCE, SUSPENDED_AT));
    aStartedModule();
    check.check(ADAPTER_ID);
    cluster.questions.clear();

    check.check(ADAPTER_ID);

    assertEquals(List.of("suspended [%s]".formatted(PROCESS_ID)), cluster.questions);
    verify(publisher).publishWorkflowSuspension(any(), any(), any(), any(), any(), any());

  }

  @Test
  @DisplayName("An instance which left the set and runs again is reported as activated, by whom and when the audit log says")
  public void aResumedInstanceIsReportedAsActivated() {

    cluster.suspended.put(INSTANCE, suspended(INSTANCE, SUSPENDED_AT));
    aStartedModule();
    check.check(ADAPTER_ID);
    final var resumedAt = NOW.minusMinutes(5);
    cluster.suspended.clear();
    cluster.all.put(INSTANCE, withState(INSTANCE, State.ACTIVE));
    cluster.auditLog.put(INSTANCE, new SuspensionChange(INSTANCE, false, resumedAt, "ops"));

    check.check(ADAPTER_ID);

    verify(publisher)
        .publishWorkflowSuspension(
            any(), eq(WorkflowEventKind.ACTIVATED), any(), eq(resumedAt), eq("ops"), eq(EventTransaction.NEW));
    verify(publisher)
        .publishUserTaskSuspension(
            any(), eq(UserTaskEventKind.ACTIVATED), any(), eq(resumedAt), eq("ops"), eq(EventTransaction.NEW));

  }

  @Test
  @DisplayName("Without an entry in the audit log nobody is named, and an activation carries the time of the run")
  public void withoutTheAuditLogNobodyIsNamed() {

    cluster.suspended.put(INSTANCE, suspended(INSTANCE, SUSPENDED_AT));
    aStartedModule();
    check.check(ADAPTER_ID);
    verify(publisher)
        .publishWorkflowSuspension(any(), eq(WorkflowEventKind.SUSPENDED), any(), eq(SUSPENDED_AT), eq(null), any());
    cluster.suspended.clear();
    cluster.all.put(INSTANCE, withState(INSTANCE, State.ACTIVE));

    check.check(ADAPTER_ID);

    verify(publisher)
        .publishWorkflowSuspension(any(), eq(WorkflowEventKind.ACTIVATED), any(), eq(NOW), eq(null), any());

  }

  @Test
  @DisplayName("An audit log which cannot be read names nobody and stops nothing")
  public void anUnreadableAuditLogStopsNothing() {

    cluster.auditLogFailure = new IllegalStateException("403 Forbidden");
    cluster.suspended.put(INSTANCE, suspended(INSTANCE, SUSPENDED_AT));
    aStartedModule();

    check.check(ADAPTER_ID);

    verify(publisher)
        .publishWorkflowSuspension(any(), eq(WorkflowEventKind.SUSPENDED), any(), eq(SUSPENDED_AT), eq(null), any());

  }

  @Test
  @DisplayName("An instance which ended while it was suspended is forgotten without a report")
  public void anEndedInstanceIsForgotten() {

    cluster.suspended.put(INSTANCE, suspended(INSTANCE, SUSPENDED_AT));
    aStartedModule();
    check.check(ADAPTER_ID);
    cluster.suspended.clear();
    cluster.all.put(INSTANCE, withState(INSTANCE, State.ENDED));

    check.check(ADAPTER_ID);
    cluster.questions.clear();
    check.check(ADAPTER_ID);

    verify(publisher, never()).publishWorkflowSuspension(any(), eq(WorkflowEventKind.ACTIVATED), any(), any(), any(),
        any());
    assertEquals(List.of("suspended [%s]".formatted(PROCESS_ID)), cluster.questions);

  }

  @Test
  @DisplayName("The first run after a start finds what the audit log says was resumed while nobody watched")
  public void theFirstRunReadsTheAuditLogBack() {

    cluster.resumedWhileDown.add(INSTANCE);
    cluster.all.put(INSTANCE, withState(INSTANCE, State.ACTIVE));
    aStartedModule();

    check.check(ADAPTER_ID);

    assertTrue(
        cluster.questions.contains("resumed since "
            + NOW.minus(Camunda8SuspensionCheck.LOOK_BACK_AFTER_A_START)),
        cluster.questions.toString());
    verify(publisher).publishWorkflowSuspension(any(), eq(WorkflowEventKind.ACTIVATED), any(), any(), any(), any());

  }

  @Test
  @DisplayName("A search which failed leaves the set as it was, so the next run reports the same change")
  public void aFailedSearchKeepsTheSet() {

    aStartedModule();
    check.check(ADAPTER_ID);
    cluster.suspended.put(INSTANCE, suspended(INSTANCE, SUSPENDED_AT));
    cluster.failure = new IllegalStateException("the cluster is down");

    check.check(ADAPTER_ID);
    verifyNoInteractions(publisher);
    cluster.failure = null;
    check.check(ADAPTER_ID);

    verify(publisher).publishWorkflowSuspension(any(), eq(WorkflowEventKind.SUSPENDED), any(), any(), any(), any());

  }

  @Test
  @DisplayName("An instance suspended again between two runs is reported with its new moment")
  public void suspendedAgainBetweenTwoRuns() {

    cluster.suspended.put(INSTANCE, suspended(INSTANCE, SUSPENDED_AT));
    aStartedModule();
    check.check(ADAPTER_ID);
    final var again = NOW.minusMinutes(1);
    cluster.suspended.put(INSTANCE, suspended(INSTANCE, again));

    check.check(ADAPTER_ID);

    verify(publisher).publishWorkflowSuspension(any(), eq(WorkflowEventKind.SUSPENDED), any(), eq(again), any(), any());

  }

  @Test
  @DisplayName("An instance without the aggregate's id is not reported, because every report names its case")
  public void anInstanceWithoutTheAggregateIdIsNotReported() {

    cluster.variables.clear();
    cluster.suspended.put(INSTANCE, suspended(INSTANCE, SUSPENDED_AT));
    aStartedModule();

    check.check(ADAPTER_ID);

    verifyNoInteractions(publisher);

  }

  @Test
  @DisplayName("A called instance with an aggregate of its own is a case of its own and reported as a workflow")
  public void aCalledInstanceWithItsOwnAggregateIsACase() {

    final var called = new Instance(INSTANCE, PROCESS_ID, 3, 4711L, 4711L, State.SUSPENDED, SUSPENDED_AT);
    cluster.suspended.put(INSTANCE, called);
    aStartedModule();

    check.check(ADAPTER_ID);

    verify(publisher).publishWorkflowSuspension(any(), eq(WorkflowEventKind.SUSPENDED), any(), any(), any(), any());

  }

  @Test
  @DisplayName("A module which stopped is no longer asked about")
  public void aStoppedModuleIsNotAskedAbout() {

    aStartedModule();
    check.moduleStopped(ADAPTER_ID, MODULE_ID);
    cluster.questions.clear();

    check.check(ADAPTER_ID);

    assertEquals(List.of(), cluster.questions);

  }

  @Test
  @DisplayName("A cluster of a line which cannot suspend is never asked")
  public void aLineWhichCannotSuspendIsNeverAsked() {

    cluster.canSuspend = false;
    aStartedModule();

    check.check(ADAPTER_ID);

    assertEquals(List.of(), cluster.questions);

  }

  @Test
  @DisplayName("The schedule runs the check by itself, and an interval of 0 switches it off")
  public void theScheduleRunsTheCheck() {

    interval = Duration.ZERO;
    cluster.suspended.put(INSTANCE, suspended(INSTANCE, SUSPENDED_AT));
    aStartedModule();
    assertEquals(List.of(), cluster.questions);
    check.moduleStopped(ADAPTER_ID, MODULE_ID);

    interval = Duration.ofMillis(50);
    aStartedModule();

    verify(publisher, timeout(10_000))
        .publishWorkflowSuspension(any(), eq(WorkflowEventKind.SUSPENDED), any(), any(), any(), any());

  }

  @Test
  @DisplayName("The interval is read as ISO-8601, with a unit or as milliseconds, and 0 switches the check off")
  public void theIntervalIsRead() {

    final var intervals = Camunda8SuspensionCheckInterval
        .intervalsOf(
            Map.of("a", "PT30M", "b", "2h", "c", "0", "d", "90s", "e", "1500", "f", "1d", "g", "250ms", "h", "5m"),
            List.of("a", "b", "c", "d", "e", "f", "g", "h"));

    assertEquals(Duration.ofMinutes(30), intervals.get("a"));
    assertEquals(Duration.ofHours(2), intervals.get("b"));
    assertEquals(Duration.ZERO, intervals.get("c"));
    assertEquals(Duration.ofSeconds(90), intervals.get("d"));
    assertEquals(Duration.ofMillis(1500), intervals.get("e"));
    assertEquals(Duration.ofDays(1), intervals.get("f"));
    assertEquals(Duration.ofMillis(250), intervals.get("g"));
    assertEquals(Duration.ofMinutes(5), intervals.get("h"));
    assertNull(Camunda8SuspensionCheckInterval.intervalsOf(Map.of("a", " "), List.of("a")).get("a"));

  }

  @Test
  @DisplayName("A wrong interval, and one at an adapter of another type, end the start with a message naming each key")
  public void aWrongIntervalEndsTheStart() {

    final var refused = assertThrows(
        IllegalStateException.class, () -> Camunda8SuspensionCheckInterval
            .intervalsOf(Map.of("a", "soon", "b", "-PT1H", "c7", "PT1H"), List.of("a", "b")));

    assertTrue(
        refused.getMessage()
            .contains("'vanillabp.adapters.a.cockpit.suspension-check-interval' is 'soon', which is no span of time"),
        refused.getMessage());
    assertTrue(
        refused.getMessage().contains("'vanillabp.adapters.b.cockpit.suspension-check-interval' is '-PT1H'"),
        refused.getMessage());
    assertTrue(
        refused.getMessage().contains(
            "'vanillabp.adapters.c7.cockpit.suspension-check-interval' stands at an adapter id which is no Camunda 8 adapter"),
        refused.getMessage());
    assertTrue(refused.getMessage().contains("Move it to one of: a, b."), refused.getMessage());

  }

  @Test
  @DisplayName("An instance the adapter found suspended is reported at once, with its task and the event id of a run")
  public void anInstanceTheAdapterFoundIsReportedAtOnce() {

    cluster.suspended.put(INSTANCE, suspended(INSTANCE, SUSPENDED_AT));
    cluster.all.put(INSTANCE, suspended(INSTANCE, SUSPENDED_AT));
    cluster.auditLog.put(INSTANCE, new SuspensionChange(INSTANCE, true, SUSPENDED_AT, "demo"));
    aStartedModule();

    check.reportFoundSuspended(ADAPTER_ID, MODULE_ID, INSTANCE, "4711");

    final var eventId = "suspended-%d-%d".formatted(INSTANCE, SUSPENDED_AT.toInstant().toEpochMilli());
    verify(publisher)
        .publishWorkflowSuspension(
            eq(new WorkflowReference(ADAPTER_ID, MODULE_ID, PROCESS_ID, "3", "4711", String.valueOf(INSTANCE))),
            eq(WorkflowEventKind.SUSPENDED), eq(eventId), eq(SUSPENDED_AT), eq("demo"), eq(EventTransaction.NEW));
    verify(publisher)
        .publishUserTaskSuspension(
            any(), eq(UserTaskEventKind.SUSPENDED), eq(eventId), eq(SUSPENDED_AT), eq("demo"),
            eq(EventTransaction.NEW));
    assertTrue(
        cluster.questions.stream().noneMatch(question -> question.startsWith("variables")),
        "the adapter named the aggregate, so its variable is not searched: %s".formatted(cluster.questions));

  }

  @Test
  @DisplayName("Once a run or an earlier refusal reported an instance, the adapter's repetitions ask nothing")
  public void aKnownInstanceIsNotReportedAgain() {

    cluster.suspended.put(INSTANCE, suspended(INSTANCE, SUSPENDED_AT));
    cluster.all.put(INSTANCE, suspended(INSTANCE, SUSPENDED_AT));
    aStartedModule();
    check.check(ADAPTER_ID);
    cluster.questions.clear();

    // a waiting outbox entry finds the workflow suspended every 30 seconds
    check.reportFoundSuspended(ADAPTER_ID, MODULE_ID, INSTANCE, "4711");
    check.reportFoundSuspended(ADAPTER_ID, MODULE_ID, INSTANCE, "4711");

    assertTrue(cluster.questions.isEmpty(), "nothing is asked: %s".formatted(cluster.questions));
    verify(publisher).publishWorkflowSuspension(any(), any(), any(), any(), any(), any());

  }

  @Test
  @DisplayName("An instance reported by a refusal is known to the next run, which reports nothing new")
  public void theNextRunKnowsTheInstance() {

    aStartedModule();
    check.check(ADAPTER_ID);
    cluster.suspended.put(INSTANCE, suspended(INSTANCE, SUSPENDED_AT));
    cluster.all.put(INSTANCE, suspended(INSTANCE, SUSPENDED_AT));
    check.reportFoundSuspended(ADAPTER_ID, MODULE_ID, INSTANCE, null);
    cluster.questions.clear();

    check.check(ADAPTER_ID);

    assertEquals(List.of("suspended [%s]".formatted(PROCESS_ID)), cluster.questions);
    verify(publisher).publishWorkflowSuspension(any(), any(), any(), any(), any(), any());

  }

  @Test
  @DisplayName("An instance which runs again when the report is made is left to the next run")
  public void anInstanceWhichRunsAgainIsNotReported() {

    cluster.all.put(INSTANCE, withState(INSTANCE, State.ACTIVE));
    aStartedModule();

    check.reportFoundSuspended(ADAPTER_ID, MODULE_ID, INSTANCE, "4711");

    verifyNoInteractions(publisher);

  }

  @Test
  @DisplayName("The report runs on the thread of the check, also where the interval is 0, and never fails the caller")
  public void theReportRunsOnTheThreadOfTheCheck() {

    interval = Duration.ZERO;
    cluster.all.put(INSTANCE, suspended(INSTANCE, SUSPENDED_AT));
    aStartedModule();

    check.foundSuspended(ADAPTER_ID, MODULE_ID, INSTANCE, "4711");

    verify(publisher, timeout(5_000))
        .publishWorkflowSuspension(any(), eq(WorkflowEventKind.SUSPENDED), any(), any(), any(), any());

    // a check which is closed takes nothing, and says nothing to the caller
    check.close();
    check.foundSuspended(ADAPTER_ID, MODULE_ID, INSTANCE, "4711");

  }

}
