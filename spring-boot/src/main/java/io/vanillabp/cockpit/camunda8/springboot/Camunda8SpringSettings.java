package io.vanillabp.cockpit.camunda8.springboot;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;

import io.vanillabp.camunda8.springboot.client.VanillaBpCamunda8Properties;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitSettings;
import io.vanillabp.cockpit.camunda8.Camunda8SuspensionCheckInterval;

/**
 * What the configuration says on Spring Boot: the Camunda 8 adapter's keys, and the one key of this
 * extension.
 */
public class Camunda8SpringSettings implements Camunda8CockpitSettings {

  private final VanillaBpCamunda8Properties properties;

  private final Map<String, Duration> suspensionCheckIntervals;

  /**
   * Reads the settings, and ends the start where the key of this extension is written wrong.
   *
   * @param properties The Camunda 8 adapter's own overlay of the <code>vanillabp</code>
   *          configuration tree
   * @param suspensionCheckIntervals What the application wrote at
   *          <code>vanillabp.adapters.&lt;id&gt;.cockpit.suspension-check-interval</code>, by
   *          adapter id
   * @param camunda8AdapterIds The configured adapter ids of type <code>camunda8</code>
   * @throws IllegalStateException Where a value of that key is no span of time, or stands at an
   *           adapter id of another type
   */
  public Camunda8SpringSettings(
      final VanillaBpCamunda8Properties properties,
      final Map<String, String> suspensionCheckIntervals,
      final Collection<String> camunda8AdapterIds) {

    this.properties = properties;
    this.suspensionCheckIntervals = Camunda8SuspensionCheckInterval
        .intervalsOf(suspensionCheckIntervals, camunda8AdapterIds);

  }

  @Override
  public Duration listenerJobTimeout(
      final String workflowModuleId,
      final String adapterId) {

    // asked at the workflow module rather than per workflow: one worker serves a job type
    // across every process using it, so a lock resolved per process would be several
    return properties.jobTimeoutFor(workflowModuleId, null, null, adapterId);

  }

  @Override
  public Duration suspensionCheckInterval(
      final String adapterId) {

    return suspensionCheckIntervals.getOrDefault(adapterId, Camunda8SuspensionCheckInterval.DEFAULT);

  }

}
