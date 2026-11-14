package io.vanillabp.cockpit.camunda8;

import java.time.Duration;

/**
 * What the extension has to know about a configured Camunda 8 adapter and the adapter cannot tell
 * it.
 * <p>
 * Everything else comes from the adapter itself, through its client factory: the client, its
 * configuration and the lock of a job (<code>job-timeout</code>, see
 * {@code Camunda8CockpitWorkers}). This extension reads none of the adapter's keys a second time.
 * <p>
 * How often the extension asks a cluster about suspended process instances is the one key of
 * its own, <code>vanillabp.adapters.&lt;id&gt;.cockpit.suspension-check-interval</code>. The
 * platform modules of this repository bind it. See {@link Camunda8SuspensionCheck}.
 */
@FunctionalInterface
public interface Camunda8CockpitSettings {

  /**
   * How often the extension asks the cluster of one adapter which process instances are
   * suspended.
   *
   * @param adapterId The configured adapter id
   * @return The interval, {@link Duration#ZERO} where the check is switched off. The default is
   *         {@link Camunda8SuspensionCheckInterval#DEFAULT}
   */
  Duration suspensionCheckInterval(
      String adapterId);

}
