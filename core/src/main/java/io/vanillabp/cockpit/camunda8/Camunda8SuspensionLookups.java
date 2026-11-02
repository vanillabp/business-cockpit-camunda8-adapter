package io.vanillabp.cockpit.camunda8;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The questions {@link Camunda8SuspensionCheck} asks one cluster. Only a cluster of the 8.10 line
 * can suspend a process instance, and only the 8.10 client has the words for it. So the answers
 * come from {@link Camunda8SuspendedInstances} in the per-line sources, and this interface keeps
 * the check itself the same on every line.
 * <p>
 * Every question names a tenant and a whole set of instances or processes at once. The check
 * asks one question per hour and per tenant, never one per instance.
 */
interface Camunda8SuspensionLookups {

  /**
   * What a process instance is, as far as the check needs it.
   */
  enum State {
    /** It runs. */
    ACTIVE,
    /** Somebody suspended it, and nobody resumed it yet. */
    SUSPENDED,
    /** It completed or was terminated, or the storage holds it no longer. */
    ENDED
  }

  /**
   * One process instance as the searchable storage holds it.
   *
   * @param processInstanceKey The key of the instance
   * @param scopedBpmnProcessId Its process, as the cluster knows it
   * @param processVersion The version of its process, or <code>null</code> where the storage
   *          says none
   * @param parentProcessInstanceKey The instance which called it, or <code>null</code>
   * @param rootProcessInstanceKey The root of its call hierarchy, or <code>null</code> where it is
   *          the root itself
   * @param state What it is now
   * @param suspendedAt When it was suspended, or <code>null</code> where it is not suspended
   */
  record Instance(
                  long processInstanceKey,
                  String scopedBpmnProcessId,
                  Integer processVersion,
                  Long parentProcessInstanceKey,
                  Long rootProcessInstanceKey,
                  State state,
                  OffsetDateTime suspendedAt) {
  }

  /**
   * One open user task of a process instance.
   *
   * @param userTaskKey The key of the task
   * @param processInstanceKey The instance the task sits in, not the case above it
   * @param rootProcessInstanceKey The root of the call hierarchy, or <code>null</code> where the
   *          task's instance is the root
   * @param scopedBpmnProcessId The process of the task, as the cluster knows it
   * @param processVersion The version of that process, or <code>null</code>
   * @param elementId The BPMN element id of the task
   * @param externalFormReference The task definition, as the cluster knows it
   */
  record OpenUserTask(
                      long userTaskKey,
                      long processInstanceKey,
                      Long rootProcessInstanceKey,
                      String scopedBpmnProcessId,
                      Integer processVersion,
                      String elementId,
                      String externalFormReference) {
  }

  /**
   * The youngest suspension or resumption of one instance in the audit log.
   *
   * @param processInstanceKey The instance
   * @param suspended <code>true</code> for a suspension, <code>false</code> for a resumption
   * @param at When the cluster did it
   * @param by Who asked for it, or <code>null</code> where the entry names nobody
   */
  record SuspensionChange(
                          long processInstanceKey,
                          boolean suspended,
                          OffsetDateTime at,
                          String by) {
  }

  /**
   * Whether the clusters of this release line can suspend a process instance at all.
   *
   * @return <code>false</code> on 8.8 and 8.9. Then no other method is asked
   */
  boolean theClusterCanSuspend();

  /**
   * Every suspended instance of some processes, in one search which is read page by page.
   *
   * @param tenantId The tenant, or <code>null</code> where the adapter uses none
   * @param scopedBpmnProcessIds The processes, as the cluster knows them. Never empty
   * @return The instances by their key
   */
  Map<Long, Instance> suspendedInstancesOf(
      String tenantId,
      Collection<String> scopedBpmnProcessIds);

  /**
   * Some instances by their keys, in one search.
   *
   * @param tenantId The tenant, or <code>null</code>
   * @param processInstanceKeys The instances. Never empty
   * @return What the storage holds of them, by their key. An instance the storage does not hold
   *         is missing from the answer
   */
  Map<Long, Instance> instancesOf(
      String tenantId,
      Collection<Long> processInstanceKeys);

  /**
   * The open user tasks which sit in some instances, in one search. A task of an instance which
   * one of them called is not part of the answer.
   *
   * @param tenantId The tenant, or <code>null</code>
   * @param processInstanceKeys The instances. Never empty
   * @return The tasks
   */
  List<OpenUserTask> openUserTasksOf(
      String tenantId,
      Collection<Long> processInstanceKeys);

  /**
   * The values of some variables at the scope of some instances, in one search.
   *
   * @param tenantId The tenant, or <code>null</code>
   * @param processInstanceKeys The instances. Never empty
   * @param names The names of the variables. Never empty
   * @return The value of each name by instance, as text
   */
  Map<Long, Map<String, String>> variablesOf(
      String tenantId,
      Collection<Long> processInstanceKeys,
      Collection<String> names);

  /**
   * The youngest suspension or resumption of some instances, from the cluster's audit log. The
   * audit log names only what an authenticated request did. A cluster with an unprotected API
   * writes none, and then the answer is empty.
   *
   * @param tenantId The tenant, or <code>null</code>
   * @param processInstanceKeys The instances. Never empty
   * @return The youngest change by instance
   */
  Map<Long, SuspensionChange> lastSuspensionChangesOf(
      String tenantId,
      Collection<Long> processInstanceKeys);

  /**
   * The instances of some processes which the audit log says somebody resumed since a moment.
   * The check asks this once after the application started, to find a resumption which happened
   * while it was down.
   *
   * @param tenantId The tenant, or <code>null</code>
   * @param scopedBpmnProcessIds The processes, as the cluster knows them. Never empty
   * @param since The moment to look back to
   * @return The keys of the instances
   */
  List<Long> resumedSince(
      String tenantId,
      Collection<String> scopedBpmnProcessIds,
      OffsetDateTime since);

}
