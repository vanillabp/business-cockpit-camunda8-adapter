package io.vanillabp.cockpit.camunda8;

import java.time.Duration;

/**
 * What the extension has to know about a configured Camunda 8 adapter and cannot read from the
 * cluster.
 * <p>
 * There are two such questions, and the platform modules of this repository answer them. The
 * adapter's configuration overlay (<code>vanillabp.adapters.&lt;id&gt;.*</code>) is bound
 * differently on Spring Boot than on Quarkus.
 * <p>
 * The lock of a listener job is the adapter's own <code>job-timeout</code>, and this extension
 * deliberately adds no lock setting of its own. A workflow module which raised the adapter's job
 * timeout raised it for the cockpit's listeners as well.
 * <p>
 * How often the extension asks a cluster about suspended process instances is the one key of
 * its own, <code>vanillabp.adapters.&lt;id&gt;.cockpit.suspension-check-interval</code>. See
 * {@link Camunda8SuspensionCheck}.
 */
public interface Camunda8CockpitSettings {

  /**
   * How long a listener job of this extension stays locked for its worker.
   *
   * @param workflowModuleId The workflow module the worker was opened for
   * @param adapterId The configured adapter id
   * @return What <code>job-timeout</code> resolves to at the workflow module, or at the
   *         adapter, or the adapter's own default where neither says anything
   */
  Duration listenerJobTimeout(
      String workflowModuleId,
      String adapterId);

  /**
   * How often the extension asks the cluster of one adapter which process instances are
   * suspended.
   *
   * @param adapterId The configured adapter id
   * @return The interval, {@link Duration#ZERO} where the check is switched off. The default is
   *         {@link Camunda8SuspensionCheckInterval#DEFAULT}
   */
  default Duration suspensionCheckInterval(
      final String adapterId) {

    return Camunda8SuspensionCheckInterval.DEFAULT;

  }

}
