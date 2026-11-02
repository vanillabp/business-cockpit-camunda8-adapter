package io.vanillabp.cockpit.camunda8;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import io.camunda.client.api.search.enums.AuditLogEntityTypeEnum;
import io.camunda.client.api.search.enums.AuditLogOperationTypeEnum;
import io.camunda.client.api.search.enums.AuditLogResultEnum;
import io.camunda.client.api.search.enums.ProcessInstanceState;
import io.camunda.client.api.search.enums.UserTaskState;
import io.camunda.client.api.search.filter.AuditLogFilter;
import io.camunda.client.api.search.page.AnyPage;
import io.camunda.client.api.search.response.AuditLogResult;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.client.api.search.response.SearchResponse;

/**
 * What a cluster of the 8.10 line says about suspended process instances. This is the 8.10
 * variant, and it is the only one which asks anything.
 * <p>
 * A cluster of this line suspends an instance over REST or in Operate, and it tells no client
 * about it: no listener runs and no job is created. The search shows it. The instance gets the
 * state <code>SUSPENDED</code> and a <code>suspendedDate</code>, and both go away on the resume.
 * <p>
 * Every question here is one search, read page by page, never one request per instance. A list
 * of keys goes into the filter with <code>in</code>. Measured against
 * <code>camunda/camunda:8.10.7</code> on 2026-11-02 with H2: the cluster took up to 10,000 keys
 * in one filter and answered in 210 ms, and a page of 10,000 items was accepted as well. A list
 * is still cut into parts of {@link #AT_MOST}, so one request stays small on every storage.
 */
final class Camunda8SuspendedInstances implements Camunda8SuspensionLookups {

  /** How many items a page asks for, and how many keys go into one filter. */
  static final int AT_MOST = 1_000;

  private final Camunda8Clients.Cluster cluster;

  /**
   * @param cluster The cluster to ask
   */
  Camunda8SuspendedInstances(
      final Camunda8Clients.Cluster cluster) {

    this.cluster = cluster;

  }

  @Override
  public boolean theClusterCanSuspend() {

    return true;

  }

  @Override
  public Map<Long, Instance> suspendedInstancesOf(
      final String tenantId,
      final Collection<String> scopedBpmnProcessIds) {

    final var ids = List.copyOf(scopedBpmnProcessIds);
    final var found = new LinkedHashMap<Long, Instance>();
    allPages(
        page -> cluster
            .client()
            .newProcessInstanceSearchRequest()
            .filter(filter -> {
              filter.state(ProcessInstanceState.SUSPENDED);
              filter.processDefinitionId(property -> property.in(ids));
              if (tenantId != null) {
                filter.tenantId(tenantId);
              }
            })
            .page(page)
            .send()
            .join())
        .forEach(instance -> found.put(instance.getProcessInstanceKey(), instanceOf(instance)));
    return found;

  }

  @Override
  public Map<Long, Instance> instancesOf(
      final String tenantId,
      final Collection<Long> processInstanceKeys) {

    final var found = new LinkedHashMap<Long, Instance>();
    for (final var keys : inParts(processInstanceKeys)) {
      allPages(
          page -> cluster
              .client()
              .newProcessInstanceSearchRequest()
              .filter(filter -> {
                filter.processInstanceKey(property -> property.in(keys));
                if (tenantId != null) {
                  filter.tenantId(tenantId);
                }
              })
              .page(page)
              .send()
              .join())
          .forEach(instance -> found.put(instance.getProcessInstanceKey(), instanceOf(instance)));
    }
    return found;

  }

  @Override
  public List<OpenUserTask> openUserTasksOf(
      final String tenantId,
      final Collection<Long> processInstanceKeys) {

    final var found = new ArrayList<OpenUserTask>();
    for (final var keys : inParts(processInstanceKeys)) {
      allPages(
          page -> cluster
              .client()
              .newUserTaskSearchRequest()
              .filter(filter -> {
                filter.processInstanceKey(property -> property.in(keys));
                filter.state(UserTaskState.CREATED);
                if (tenantId != null) {
                  filter.tenantId(tenantId);
                }
              })
              .page(page)
              .send()
              .join())
          .forEach(
              task -> found
                  .add(
                      new OpenUserTask(
                          task.getUserTaskKey(), task.getProcessInstanceKey(), cluster
                              .callHierarchy()
                              .rootProcessInstanceKeyOf(task), task.getBpmnProcessId(), task
                                  .getProcessDefinitionVersion(), task.getElementId(), task
                                      .getExternalFormReference())));
    }
    return found;

  }

  @Override
  public Map<Long, Map<String, String>> variablesOf(
      final String tenantId,
      final Collection<Long> processInstanceKeys,
      final Collection<String> names) {

    final var wanted = List.copyOf(names);
    final var found = new HashMap<Long, Map<String, String>>();
    for (final var keys : inParts(processInstanceKeys)) {
      allPages(
          page -> cluster
              .client()
              .newVariableSearchRequest()
              .filter(filter -> {
                filter.processInstanceKey(property -> property.in(keys));
                filter.name(property -> property.in(wanted));
                if (tenantId != null) {
                  filter.tenantId(tenantId);
                }
              })
              .page(page)
              .send()
              .join())
          .stream()
          // a variable of the instance itself and not one of an element inside it, which may
          // carry the same name
          .filter(variable -> variable.getProcessInstanceKey().equals(variable.getScopeKey()))
          .forEach(
              variable -> found
                  .computeIfAbsent(variable.getProcessInstanceKey(), key -> new HashMap<>())
                  .put(variable.getName(), String.valueOf(variable.getValueAsType(Object.class))));
    }
    return found;

  }

  @Override
  public Map<Long, SuspensionChange> lastSuspensionChangesOf(
      final String tenantId,
      final Collection<Long> processInstanceKeys) {

    final var youngest = new HashMap<Long, SuspensionChange>();
    for (final var keys : inParts(processInstanceKeys)) {
      final var asText = keys.stream().map(String::valueOf).toList();
      allPages(
          page -> cluster
              .client()
              .newAuditLogSearchRequest()
              .filter(filter -> {
                filter.processInstanceKey(property -> property.in(asText));
                suspensionsOnly(filter, tenantId);
              })
              .sort(sort -> sort.timestamp().asc())
              .page(page)
              .send()
              .join())
          // sorted by time, so a later entry of the same instance replaces an earlier one
          .forEach(entry -> changeOf(entry).ifPresent(change -> youngest.put(change.processInstanceKey(), change)));
    }
    return youngest;

  }

  @Override
  public List<Long> resumedSince(
      final String tenantId,
      final Collection<String> scopedBpmnProcessIds,
      final OffsetDateTime since) {

    final var ids = List.copyOf(scopedBpmnProcessIds);
    final var youngest = new LinkedHashMap<Long, SuspensionChange>();
    allPages(
        page -> cluster
            .client()
            .newAuditLogSearchRequest()
            .filter(filter -> {
              filter.processDefinitionId(property -> property.in(ids));
              filter.timestamp(property -> property.gte(since));
              suspensionsOnly(filter, tenantId);
            })
            .sort(sort -> sort.timestamp().asc())
            .page(page)
            .send()
            .join())
        .forEach(entry -> changeOf(entry).ifPresent(change -> youngest.put(change.processInstanceKey(), change)));
    return youngest
        .values()
        .stream()
        .filter(change -> !change.suspended())
        .map(SuspensionChange::processInstanceKey)
        .toList();

  }

  /**
   * The conditions every search of the audit log here shares: a suspension or a resumption of a
   * process instance which the cluster carried out. A refused command is in the audit log as
   * well, with another result.
   */
  private static void suspensionsOnly(
      final AuditLogFilter filter,
      final String tenantId) {

    filter.operationType(property -> property.in(AuditLogOperationTypeEnum.SUSPEND, AuditLogOperationTypeEnum.RESUME));
    filter.entityType(AuditLogEntityTypeEnum.PROCESS_INSTANCE);
    filter.result(AuditLogResultEnum.SUCCESS);
    if (tenantId != null) {
      filter.tenantId(tenantId);
    }

  }

  private static Optional<SuspensionChange> changeOf(
      final AuditLogResult entry) {

    final var key = entry.getProcessInstanceKey();
    if ((key == null) || (entry.getTimestamp() == null)) {
      return Optional.empty();
    }
    try {
      return Optional
          .of(
              new SuspensionChange(
                  Long.parseLong(key), entry.getOperationType() == AuditLogOperationTypeEnum.SUSPEND, entry
                      .getTimestamp(), entry.getActorId()));
    } catch (final NumberFormatException e) {
      return Optional.empty();
    }

  }

  private static Instance instanceOf(
      final ProcessInstance instance) {

    final var root = instance.getRootProcessInstanceKey();
    return new Instance(
        instance.getProcessInstanceKey(), instance.getProcessDefinitionId(), instance
            .getProcessDefinitionVersion(), instance.getParentProcessInstanceKey(), (root == null) || root
                .equals(instance.getProcessInstanceKey())
                    ? null
                    : root, stateOf(instance.getState()), instance.getSuspendedDate());

  }

  private static State stateOf(
      final ProcessInstanceState state) {

    if (state == ProcessInstanceState.SUSPENDED) {
      return State.SUSPENDED;
    }
    if (state == ProcessInstanceState.ACTIVE) {
      return State.ACTIVE;
    }
    // COMPLETED, TERMINATED, and a literal a newer cluster may add. None of them can be
    // worked on, so none of them is reported as active again
    return State.ENDED;

  }

  /**
   * Cuts a list of keys into parts of {@link #AT_MOST}, without the duplicates.
   */
  private static List<List<Long>> inParts(
      final Collection<Long> keys) {

    final var distinct = List.copyOf(new LinkedHashSet<>(keys));
    final var parts = new ArrayList<List<Long>>();
    for (var from = 0; from < distinct.size(); from += AT_MOST) {
      parts.add(distinct.subList(from, Math.min(distinct.size(), from + AT_MOST)));
    }
    return parts;

  }

  /**
   * Reads every page of one search. A page shorter than {@link #AT_MOST} is the last one.
   */
  private static <T> List<T> allPages(
      final Function<Consumer<AnyPage>, SearchResponse<T>> search) {

    final var items = new ArrayList<T>();
    var after = (String) null;
    while (true) {
      final var cursor = after;
      final var response = search
          .apply(page -> {
            page.limit(AT_MOST);
            if (cursor != null) {
              page.after(cursor);
            }
          });
      items.addAll(response.items());
      if ((response.items().size() < AT_MOST) || (response.page() == null) || (response.page().endCursor() == null)) {
        return items;
      }
      after = response.page().endCursor();
    }

  }

}
