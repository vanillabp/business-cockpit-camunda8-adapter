package io.vanillabp.cockpit.camunda8;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

/**
 * Finds out which process instances somebody suspended or resumed, and tells the cockpit.
 * <p>
 * A cluster of the 8.10 line suspends an instance over REST or in Operate, and it tells no
 * client about it. No listener runs, and no job is created. So the extension has to ask. It asks
 * once per interval, one hour by default, and it asks as little as it can:
 * <ol>
 * <li>One search per adapter id and tenant: every instance in state <code>SUSPENDED</code> of
 * the processes this extension wired, read page by page.</li>
 * <li>That set is compared with the set of the run before. An instance which is new in it was
 * suspended. Its <code>suspendedDate</code> is the moment reported, so every node of the
 * application reports the same moment.</li>
 * <li>The instances which left the set were resumed or have ended. One search by their keys
 * says which. An ended instance needs nothing: its tasks end with their own listeners, and the
 * cockpit keeps the mark of a suspension on an entry which ended while suspended.</li>
 * <li>Only where something changed, three more searches follow, each for all changed instances
 * at once: the open user tasks which sit in those instances, the variable with the workflow
 * aggregate's id, and the cluster's audit log, which says who suspended or resumed them.</li>
 * </ol>
 * A run in which nothing changed is one search per adapter id and tenant.
 * <p>
 * <b>Only what is suspended itself.</b> A process instance started by a call activity is not
 * suspended with its caller. So the tasks reported are the tasks of the suspended instance, and
 * never those of an instance it called. A suspended instance which is a business case of its
 * own is reported as a suspended workflow as well. A suspended step of another case is not: the
 * case above it still runs.
 * <p>
 * <b>Who did it.</b> The audit log of the cluster names the person for an authenticated request.
 * A cluster with an unprotected API writes no entry at all, and then the cockpit is told
 * nobody. The audit log also gives the moment of a resumption. Without an entry the moment is the
 * time of the run.
 * <p>
 * <b>A restart.</b> The set of the run before lives in memory. The first run after a start
 * therefore reports every suspended instance again, which the cockpit takes as a repetition. A
 * resumption while the application was down would be missed. So the first run also reads the
 * audit log of the last {@link #LOOK_BACK_AFTER_A_START} for resumptions. On a cluster without
 * an audit log such a resumption stays unseen until the instance is suspended and resumed again.
 * <p>
 * <b>Several nodes.</b> Every node of the application runs the check. The cockpit takes a
 * second report of the same suspension as a repetition and keeps the first moment.
 * <p>
 * <b>At once, where the adapter found it.</b> An application which answers a task of a
 * suspended instance is refused by the Camunda 8 adapter, and VanillaBP tells this extension.
 * {@link #foundSuspended} then reports that one instance on the thread of this check, the same way
 * a run would, without waiting for the next run. A run afterwards finds the instance known and
 * reports nothing new (see {@code DECISIONS.pending/2074.md}).
 * <p>
 * The lines 8.8 and 8.9 cannot suspend an instance, and the check never runs there.
 */
public class Camunda8SuspensionCheck implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(Camunda8SuspensionCheck.class);

  /**
   * How far back the first run after a start reads the audit log for resumptions. A day covers a
   * restart and a deployment over night.
   */
  static final Duration LOOK_BACK_AFTER_A_START = Duration.ofDays(1);

  /**
   * When the first run starts after a module started. A run needs the models of the module wired
   * and its workers open, and a short wait keeps it out of the start itself.
   */
  static final Duration FIRST_RUN_AFTER = Duration.ofMinutes(1);

  /**
   * The processes one search asks about: one adapter id and one tenant.
   *
   * @param adapterId The configured adapter id
   * @param tenantId The tenant, or <code>null</code> where the adapter uses none
   */
  private record Scope(
                       String adapterId,
                       String tenantId) {
  }

  /**
   * One process this extension wired, as a search result is translated back.
   *
   * @param workflowModuleId The workflow module which wired it
   * @param bpmnProcessId The process id as the application wrote it
   * @param aggregateIdName The variable which carries the workflow aggregate's id
   */
  private record Wired(
                       String workflowModuleId,
                       String bpmnProcessId,
                       String aggregateIdName) {
  }

  /**
   * One change found by a run.
   *
   * @param instance The instance as the storage holds it now
   * @param suspended <code>true</code> for a suspension, <code>false</code> for a resumption
   */
  private record Change(
                        Instance instance,
                        boolean suspended) {
  }

  private final Camunda8Clients clients;

  private final Camunda8CockpitDeployments deployments;

  private final Camunda8CockpitSettings settings;

  private final Supplier<BusinessCockpitEventPublisher> publisher;

  private final Function<String, Camunda8SuspensionLookups> lookups;

  private final Supplier<OffsetDateTime> clock;

  /** The workflow modules which started, by adapter id. */
  private final Map<String, Set<String>> startedModules = new HashMap<>();

  /** The scheduled runs, by adapter id. */
  private final Map<String, ScheduledFuture<?>> runs = new HashMap<>();

  /** What the run before found suspended, per adapter id and tenant. Read by one run at a time. */
  private final Map<Scope, Map<Long, Instance>> suspendedBefore = new HashMap<>();

  /** Taken by a run, so two runs never compare against the same set. */
  private final Object oneRunAtATime = new Object();

  private ScheduledExecutorService scheduler;

  /** Set by {@link #close()}, so no later report starts the thread again. */
  private boolean closed;

  /**
   * Creates the check. It does nothing until the first workflow module starts.
   *
   * @param clients The clusters of the configured Camunda 8 adapters
   * @param deployments What this extension wired, which says which processes to ask about
   * @param settings How often to ask
   * @param publisher Where a suspension is reported, asked for per run
   */
  public Camunda8SuspensionCheck(
      final Camunda8Clients clients,
      final Camunda8CockpitDeployments deployments,
      final Camunda8CockpitSettings settings,
      final Supplier<BusinessCockpitEventPublisher> publisher) {

    this(
        clients, deployments, settings, publisher, adapterId -> clients.of(adapterId)
            .suspensions(), OffsetDateTime::now);

  }

  /**
   * Creates the check with its questions answered by somebody else, which is what a test needs.
   *
   * @param lookups Who answers the questions about one adapter id's cluster
   * @param clock What the time is
   */
  Camunda8SuspensionCheck(
      final Camunda8Clients clients,
      final Camunda8CockpitDeployments deployments,
      final Camunda8CockpitSettings settings,
      final Supplier<BusinessCockpitEventPublisher> publisher,
      final Function<String, Camunda8SuspensionLookups> lookups,
      final Supplier<OffsetDateTime> clock) {

    this.clients = clients;
    this.deployments = deployments;
    this.settings = settings;
    this.publisher = publisher;
    this.lookups = lookups;
    this.clock = clock;

  }

  /**
   * Includes the processes of one workflow module in the check of its cluster, and starts the
   * check of that cluster with its first module.
   *
   * @param adapterId The configured adapter id the module started for
   * @param workflowModuleId The workflow module which started
   */
  public synchronized void moduleStarted(
      final String adapterId,
      final String workflowModuleId) {

    if (!lookups.apply(adapterId).theClusterCanSuspend()) {
      return;
    }
    startedModules.computeIfAbsent(adapterId, id -> new LinkedHashSet<>()).add(workflowModuleId);
    if (runs.containsKey(adapterId)) {
      return;
    }
    final var interval = settings.suspensionCheckInterval(adapterId);
    if (interval.isZero()) {
      logger
          .info(
              "Camunda8[{}]: the Business Cockpit does not check for suspended workflows, because '{}' is 0. A workflow somebody suspends keeps showing its tasks as open in the cockpit",
              adapterId, Camunda8SuspensionCheckInterval.keyOf(adapterId));
      return;
    }
    final var firstRun = interval.compareTo(FIRST_RUN_AFTER) < 0
        ? interval
        : FIRST_RUN_AFTER;
    runs
        .put(
            adapterId,
            theScheduler()
                .scheduleWithFixedDelay(
                    () -> check(adapterId), firstRun.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS));
    logger
        .info(
            "Camunda8[{}]: the Business Cockpit checks every {} whether somebody suspended or resumed a workflow of this cluster ('{}', 0 switches it off). Who did it comes from the cluster's audit log, which has entries only for authenticated requests. Without such an entry the cockpit names nobody",
            adapterId, interval, Camunda8SuspensionCheckInterval.keyOf(adapterId));

  }

  /**
   * The one thread of this check, started with its first use. A run and a report of
   * {@link #foundSuspended} share it.
   */
  private synchronized ScheduledExecutorService theScheduler() {

    if (closed) {
      throw new IllegalStateException("the suspension check is closed");
    }
    if (scheduler == null) {
      scheduler = Executors
          .newSingleThreadScheduledExecutor(runnable -> {
            final var thread = new Thread(runnable, "vanillabp-businesscockpit-suspension-check");
            // a run is a search and a few reports. A shutdown which never reaches close() must
            // not be held up by it
            thread.setDaemon(true);
            return thread;
          });
    }
    return scheduler;

  }

  /**
   * Reports one instance which the Camunda 8 adapter found suspended, as a run would report it.
   * It returns at once. The caller is the thread of the application or of its outbox, maybe
   * inside a transaction which rolls back next. So the work runs on the thread of this check.
   * <p>
   * It is also done where the interval is 0. Then a suspension still shows at once, but a
   * resumption is never seen.
   *
   * @param adapterId The configured adapter id which found it
   * @param workflowModuleId The workflow module of the refused call
   * @param processInstanceKey The suspended instance
   * @param aggregateId The workflow aggregate's id, or <code>null</code> where the adapter did not
   *          know it. Then it is read from the instance's variables
   */
  public void foundSuspended(
      final String adapterId,
      final String workflowModuleId,
      final long processInstanceKey,
      final String aggregateId) {

    try {
      if (!lookups.apply(adapterId).theClusterCanSuspend()) {
        return;
      }
      theScheduler().execute(() -> reportFoundSuspended(adapterId, workflowModuleId, processInstanceKey, aggregateId));
    } catch (final RuntimeException e) {
      // a check which is closed already, or a cluster which is not configured. The hourly run
      // is the net, so this is no reason to bother the caller
      logger
          .debug(
              "Camunda8[{}]: the Business Cockpit could not take process instance {} to report it as suspended",
              adapterId, processInstanceKey, e);
    }

  }

  /**
   * The work of {@link #foundSuspended}, on the thread of this check.
   */
  void reportFoundSuspended(
      final String adapterId,
      final String workflowModuleId,
      final long processInstanceKey,
      final String aggregateId) {

    synchronized (oneRunAtATime) {
      try {
        final var scope = new Scope(adapterId, clients.of(adapterId).scope().tenantIdOf(workflowModuleId));
        final var before = suspendedBefore.get(scope);
        if ((before != null) && before.containsKey(processInstanceKey)) {
          // reported already, by a run or by an earlier refusal. An outbox entry which waits
          // ends up here every 30 seconds
          return;
        }
        final var processes = processesToAskAbout(adapterId).getOrDefault(scope.tenantId(), Map.of());
        final var cluster = lookups.apply(adapterId);
        final var instance = cluster.instancesOf(scope.tenantId(), Set.of(processInstanceKey)).get(processInstanceKey);
        if ((instance == null) || (instance.state() != State.SUSPENDED)) {
          // resumed meanwhile, or the storage does not show it yet. The next run decides
          return;
        }
        final var changes = new LinkedHashMap<Long, Change>();
        changes.put(processInstanceKey, new Change(instance, true));
        report(
            scope, processes, cluster, changes, aggregateId == null
                ? Map.of()
                : Map.of(processInstanceKey, aggregateId));
        // known from now on, so the next run reports nothing new. Before the first run there is
        // no set yet. That run reports every suspended instance anyway, and the cockpit takes the
        // report as a repetition, because its event id is the same
        if (before != null) {
          before.put(processInstanceKey, instance);
        }
      } catch (final RuntimeException e) {
        logger
            .warn(
                "Camunda8[{}]: the Business Cockpit could not report process instance {} as suspended. The next check finds it",
                adapterId, processInstanceKey, e);
      }
    }

  }

  /**
   * Leaves the processes of one workflow module out of the check of its cluster, and stops that
   * check with the last module.
   *
   * @param adapterId The configured adapter id the module is going down for
   * @param workflowModuleId The workflow module which is going down
   */
  public synchronized void moduleStopped(
      final String adapterId,
      final String workflowModuleId) {

    final var modules = startedModules.get(adapterId);
    if (modules == null) {
      return;
    }
    modules.remove(workflowModuleId);
    if (!modules.isEmpty()) {
      return;
    }
    startedModules.remove(adapterId);
    final var run = runs.remove(adapterId);
    if (run != null) {
      run.cancel(false);
    }

  }

  /**
   * Stops every check.
   */
  @Override
  public synchronized void close() {

    closed = true;
    runs.values().forEach(run -> run.cancel(false));
    runs.clear();
    startedModules.clear();
    if (scheduler != null) {
      scheduler.shutdownNow();
      scheduler = null;
    }

  }

  /**
   * One run for one cluster. The schedule calls it, and a test calls it directly.
   *
   * @param adapterId The configured adapter id
   */
  void check(
      final String adapterId) {

    synchronized (oneRunAtATime) {
      final var processesByTenant = processesToAskAbout(adapterId);
      processesByTenant
          .forEach((
              tenantId,
              processes) -> {
            try {
              check(new Scope(adapterId, tenantId), processes);
            } catch (final RuntimeException e) {
              // the set of the run before stays as it was, so the next run finds the same
              // changes again
              logger
                  .warn(
                      "Camunda8[{}]: the Business Cockpit could not check for suspended workflows of tenant '{}'. The next check runs as scheduled",
                      adapterId, tenantId, e);
            }
          });
    }

  }

  /**
   * The processes this extension wired for the started modules of one adapter id, grouped by
   * tenant, because a search names one tenant.
   */
  private Map<String, Map<String, Wired>> processesToAskAbout(
      final String adapterId) {

    final List<String> modules;
    synchronized (this) {
      modules = List.copyOf(startedModules.getOrDefault(adapterId, Set.of()));
    }
    final var scope = clients.of(adapterId).scope();
    final var byTenant = new LinkedHashMap<String, Map<String, Wired>>();
    for (final var workflowModuleId : modules) {
      final var aggregateIdNames = deployments.aggregateIdNamesByScopedIdOf(adapterId, workflowModuleId);
      deployments
          .bpmnProcessIdsByScopedIdOf(adapterId, workflowModuleId)
          .forEach((
              scopedBpmnProcessId,
              bpmnProcessId) -> byTenant
                  .computeIfAbsent(scope.tenantIdOf(workflowModuleId), tenant -> new LinkedHashMap<>())
                  .putIfAbsent(
                      scopedBpmnProcessId,
                      new Wired(workflowModuleId, bpmnProcessId, aggregateIdNames.get(scopedBpmnProcessId))));
    }
    byTenant.values().removeIf(Map::isEmpty);
    return byTenant;

  }

  private void check(
      final Scope scope,
      final Map<String, Wired> processes) {

    final var cluster = lookups.apply(scope.adapterId());
    final var before = suspendedBefore.get(scope);
    final var now = cluster.suspendedInstancesOf(scope.tenantId(), processes.keySet());

    final var changes = new LinkedHashMap<Long, Change>();
    now
        .values()
        .stream()
        .filter(instance -> (before == null) || !before.containsKey(instance.processInstanceKey())
        // suspended again between two runs: a new moment
            || !Objects.equals(before.get(instance.processInstanceKey()).suspendedAt(), instance.suspendedAt()))
        .forEach(instance -> changes.put(instance.processInstanceKey(), new Change(instance, true)));

    final var left = new LinkedHashSet<Long>();
    if (before != null) {
      before.keySet().stream().filter(key -> !now.containsKey(key)).forEach(left::add);
    } else {
      // the first run after a start: what the audit log says was resumed while nobody watched
      fromTheAuditLog(
          scope, () -> cluster
              .resumedSince(scope.tenantId(), processes.keySet(), clock.get().minus(LOOK_BACK_AFTER_A_START)),
          List.<Long>of())
          .stream()
          .filter(key -> !now.containsKey(key))
          .forEach(left::add);
    }
    final var stillSuspended = new LinkedHashMap<>(now);
    if (!left.isEmpty()) {
      cluster
          .instancesOf(scope.tenantId(), left)
          .values()
          .forEach(instance -> {
            if (instance.state() == State.ACTIVE) {
              changes.put(instance.processInstanceKey(), new Change(instance, false));
            } else if (instance.state() == State.SUSPENDED) {
              // the two searches disagree for a moment. The next run looks again
              stillSuspended.put(instance.processInstanceKey(), instance);
            }
            // an ended instance needs nothing, see the class
          });
    }

    if (!changes.isEmpty()) {
      report(scope, processes, cluster, changes, Map.of());
    } else {
      logger
          .debug(
              "Camunda8[{}]: the Business Cockpit found no workflow of tenant '{}' suspended or resumed since the last check ({} suspended)",
              scope.adapterId(), scope.tenantId(), now.size());
    }
    // only once everything was reported. A failure leaves the set of the run before, so the next
    // run reports the same changes
    suspendedBefore.put(scope, stillSuspended);

  }

  private void report(
      final Scope scope,
      final Map<String, Wired> processes,
      final Camunda8SuspensionLookups cluster,
      final Map<Long, Change> changes,
      final Map<Long, String> knownAggregateIds) {

    final var keys = changes.keySet();
    final var whoDidIt = fromTheAuditLog(
        scope, () -> cluster.lastSuspensionChangesOf(scope.tenantId(), keys), Map.<Long, SuspensionChange>of());
    final var names = new HashSet<String>();
    changes
        .values()
        .stream()
        // an aggregate id the adapter handed over saves the search for its variable
        .filter(change -> !knownAggregateIds.containsKey(change.instance().processInstanceKey()))
        .map(change -> processes.get(change.instance().scopedBpmnProcessId()))
        .filter(Objects::nonNull)
        .map(Wired::aggregateIdName)
        .filter(Objects::nonNull)
        .forEach(names::add);
    final Map<Long, Map<String, String>> variables = names.isEmpty()
        ? Map.of()
        : cluster.variablesOf(scope.tenantId(), keys, names);
    final var tasksByInstance = new HashMap<Long, List<OpenUserTask>>();
    cluster
        .openUserTasksOf(scope.tenantId(), keys)
        .forEach(task -> tasksByInstance
            .computeIfAbsent(task.processInstanceKey(), key -> new ArrayList<>())
            .add(task));

    var suspended = 0;
    var resumed = 0;
    for (final var change : changes.values()) {
      final var instance = change.instance();
      final var wired = processes.get(instance.scopedBpmnProcessId());
      if (wired == null) {
        continue;
      }
      final var aggregateId = knownAggregateIds.containsKey(instance.processInstanceKey())
          ? knownAggregateIds.get(instance.processInstanceKey())
          : variables
              .getOrDefault(instance.processInstanceKey(), Map.of())
              .get(wired.aggregateIdName());
      if (aggregateId == null) {
        logger
            .warn(
                "Camunda8[{}]: the Business Cockpit found process instance {} of '{}/{}' {}, but it carries no value for the workflow aggregate's id variable '{}'. Every report the cockpit gets names the business case it is about, so the cockpit is not told",
                scope.adapterId(), instance.processInstanceKey(), wired.workflowModuleId(), wired.bpmnProcessId(),
                change.suspended()
                    ? "suspended"
                    : "resumed",
                wired.aggregateIdName());
        continue;
      }
      final var entry = whoDidIt.get(instance.processInstanceKey());
      final var told = (entry != null) && (entry.suspended() == change.suspended())
          ? entry
          : null;
      final var at = momentOf(change, told);
      final var by = told == null
          ? null
          : told.by();
      final var eventId = "%s-%d-%d"
          .formatted(change.suspended()
              ? "suspended"
              : "activated", instance.processInstanceKey(), at.toInstant().toEpochMilli());
      reportTheWorkflow(scope, wired, instance, aggregateId, change.suspended(), eventId, at, by);
      tasksByInstance
          .getOrDefault(instance.processInstanceKey(), List.of())
          .forEach(
              task -> reportTheTask(scope, processes, wired, task, aggregateId, change.suspended(), eventId, at, by));
      if (change.suspended()) {
        suspended++;
      } else {
        resumed++;
      }
    }
    logger
        .info(
            "Camunda8[{}]: the Business Cockpit found {} workflow(s) of tenant '{}' suspended and {} resumed since the last check, and reported them with their open user tasks",
            scope.adapterId(), suspended, scope.tenantId(), resumed);

  }

  /**
   * Asks the audit log, and answers nothing where it cannot be read. A client may lack the
   * permission to read it, and a cluster may keep none. Neither may stop the check, because the
   * set of suspended instances is what it rests on. The audit log only adds who did it and when a
   * resumption happened.
   */
  private <T> T fromTheAuditLog(
      final Scope scope,
      final Supplier<T> question,
      final T nothing) {

    try {
      return question.get();
    } catch (final RuntimeException e) {
      logger
          .info(
              "Camunda8[{}]: the Business Cockpit could not read the audit log of tenant '{}', so it names nobody as the person who suspended or resumed a workflow, and a resumption carries the time of the check. Allow the client of this adapter to read the audit log to change that",
              scope.adapterId(), scope.tenantId(), e);
      return nothing;
    }

  }

  /**
   * The moment a change is reported with. The cockpit orders the reports of one entry by it, so
   * the moment the cluster says comes first: the <code>suspendedDate</code> of a suspended
   * instance, which every node reads the same, then the entry of the audit log. A resumption the
   * audit log does not know is reported with the time of the run.
   */
  private OffsetDateTime momentOf(
      final Change change,
      final SuspensionChange told) {

    if (change.suspended() && (change.instance().suspendedAt() != null)) {
      return change.instance().suspendedAt();
    }
    if (told != null) {
      return told.at();
    }
    return clock.get();

  }

  private void reportTheWorkflow(
      final Scope scope,
      final Wired wired,
      final Instance instance,
      final String aggregateId,
      final boolean suspended,
      final String eventId,
      final OffsetDateTime at,
      final String by) {

    // the cockpit shows business cases. A suspended step of another case leaves that case
    // running. See decision 3 in the repository's DECISIONS.md
    final var theCase = caseOf(
        scope.adapterId(), wired.workflowModuleId(), instance.processInstanceKey(), instance.scopedBpmnProcessId(),
        instance.rootProcessInstanceKey(), instance.parentProcessInstanceKey() != null);
    if (!theCase.equals(instance.processInstanceKey())) {
      return;
    }
    publisher
        .get()
        .publishWorkflowSuspension(
            new WorkflowReference(
                scope.adapterId(), wired.workflowModuleId(), wired.bpmnProcessId(), versionOf(
                    instance.processVersion()), aggregateId, String.valueOf(instance.processInstanceKey())),
            suspended
                ? WorkflowEventKind.SUSPENDED
                : WorkflowEventKind.ACTIVATED,
            eventId, at, by, EventTransaction.NEW);

  }

  private void reportTheTask(
      final Scope scope,
      final Map<String, Wired> processes,
      final Wired wiredInstance,
      final OpenUserTask task,
      final String aggregateId,
      final boolean suspended,
      final String eventId,
      final OffsetDateTime at,
      final String by) {

    // the task sits in the suspended instance, so its process is that instance's process
    final var wired = processes.getOrDefault(task.scopedBpmnProcessId(), wiredInstance);
    final var workflowId = caseOf(
        scope.adapterId(), wired.workflowModuleId(), task.processInstanceKey(), task.scopedBpmnProcessId(), task
            .rootProcessInstanceKey(),
        task.rootProcessInstanceKey() != null);
    publisher
        .get()
        .publishUserTaskSuspension(
            new UserTaskReference(
                scope.adapterId(), wired.workflowModuleId(), wired.bpmnProcessId(), versionOf(
                    task.processVersion()), aggregateId, String.valueOf(workflowId), String.valueOf(task
                        .userTaskKey()), clients
                            .of(scope.adapterId())
                            .scope()
                            .plainTaskDefinitionOf(
                                wired.workflowModuleId(), wired.bpmnProcessId(), task.externalFormReference()), task
                                    .elementId()),
            suspended
                ? UserTaskEventKind.SUSPENDED
                : UserTaskEventKind.ACTIVATED,
            eventId, at, by, EventTransaction.NEW);

  }

  /**
   * The business case of one instance, which the listener jobs of this extension report the same
   * way. See decision 3 in the repository's DECISIONS.md.
   */
  private Long caseOf(
      final String adapterId,
      final String workflowModuleId,
      final long processInstanceKey,
      final String scopedBpmnProcessId,
      final Long root,
      final boolean hasACaller) {

    return clients
        .of(adapterId)
        .businessCases()
        .caseOf(
            processInstanceKey, scopedBpmnProcessId, root, hasACaller, deployments
                .bpmnProcessIdsByScopedIdOf(adapterId, workflowModuleId),
            (
                calling,
                called) -> deployments.shareTheWorkflowAggregate(adapterId, workflowModuleId, calling, called));

  }

  private static String versionOf(
      final Integer version) {

    return version == null
        ? null
        : String.valueOf(version);

  }

}
