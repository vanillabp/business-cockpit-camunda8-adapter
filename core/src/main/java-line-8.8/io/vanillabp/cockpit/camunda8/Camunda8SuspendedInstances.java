package io.vanillabp.cockpit.camunda8;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * What a cluster of the 8.8 line says about suspended process instances. This is the 8.8
 * variant. A cluster of this line cannot suspend an instance, and the client of this line knows
 * no such state. So nothing is asked, and {@link Camunda8SuspensionCheck} never runs.
 * <p>
 * An application of this line which runs against an 8.10 cluster does not see a suspension
 * either. Its cockpit keeps showing a suspended task as open.
 */
final class Camunda8SuspendedInstances implements Camunda8SuspensionLookups {

  /**
   * @param cluster The cluster, which this line never asks
   */
  Camunda8SuspendedInstances(
      final Camunda8Clients.Cluster cluster) {
  }

  @Override
  public boolean theClusterCanSuspend() {

    return false;

  }

  @Override
  public Map<Long, Instance> suspendedInstancesOf(
      final String tenantId,
      final Collection<String> scopedBpmnProcessIds) {

    return Map.of();

  }

  @Override
  public Map<Long, Instance> instancesOf(
      final String tenantId,
      final Collection<Long> processInstanceKeys) {

    return Map.of();

  }

  @Override
  public List<OpenUserTask> openUserTasksOf(
      final String tenantId,
      final Collection<Long> processInstanceKeys) {

    return List.of();

  }

  @Override
  public Map<Long, Map<String, String>> variablesOf(
      final String tenantId,
      final Collection<Long> processInstanceKeys,
      final Collection<String> names) {

    return Map.of();

  }

  @Override
  public Map<Long, SuspensionChange> lastSuspensionChangesOf(
      final String tenantId,
      final Collection<Long> processInstanceKeys) {

    return Map.of();

  }

  @Override
  public List<Long> resumedSince(
      final String tenantId,
      final Collection<String> scopedBpmnProcessIds,
      final OffsetDateTime since) {

    return List.of();

  }

}
