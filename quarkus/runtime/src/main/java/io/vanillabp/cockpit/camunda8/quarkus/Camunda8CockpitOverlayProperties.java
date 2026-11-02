package io.vanillabp.cockpit.camunda8.quarkus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigDocMapKey;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;

/**
 * The keys this extension reads itself, as an OVERLAY of the shared <code>vanillabp.*</code>
 * configuration tree on Quarkus. It is one more RUN_TIME mapping over the same prefix as the
 * platform's own and the Camunda 8 adapter's. SmallRye's check for unknown keys passes as soon as
 * one mapping declares a key, so this mapping is also what lets an application which writes the
 * key boot at all.
 * <p>
 * There is one key:
 *
 * <pre>
 * vanillabp.adapters.&lt;adapter-id&gt;.cockpit.suspension-check-interval
 * </pre>
 *
 * It is declared as text and read by {@code Camunda8SuspensionCheckInterval} in the core, so a
 * value which is no span of time gets the same message here and on Spring Boot.
 * <p>
 * The mapping is not <code>&#64;StaticInitSafe</code>, like the Business Cockpit's own overlay:
 * an overlay belongs in the same phase as the platform's mapping it overlays.
 */
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = "vanillabp")
public interface Camunda8CockpitOverlayProperties {

  /**
   * The sections of the adapter ids, as far as the Business Cockpit reads them.
   *
   * @return The sections, by adapter id
   */
  @ConfigDocMapKey("adapter-id")
  Map<String, Adapter> adapters();

  /**
   * What the application wrote for the check, by adapter id.
   *
   * @return The raw values, for the adapter ids which carry the key
   */
  default Map<String, String> suspensionCheckIntervals() {

    final var intervals = new LinkedHashMap<String, String>();
    adapters()
        .forEach((
            adapterId,
            adapter) -> adapter
                .cockpit()
                .flatMap(Cockpit::suspensionCheckInterval)
                .ifPresent(interval -> intervals.put(adapterId, interval)));
    return intervals;

  }

  /**
   * The section of one adapter id.
   */
  interface Adapter {

    /**
     * What the Business Cockpit reads for this adapter.
     *
     * @return The section <code>cockpit</code>, where the application wrote one
     */
    Optional<Cockpit> cockpit();

  }

  /**
   * <code>vanillabp.adapters.&lt;adapter-id&gt;.cockpit</code>.
   */
  interface Cockpit {

    /**
     * How often the Business Cockpit asks the cluster which workflows are suspended. 0 switches
     * the check off. The default is one hour.
     *
     * @return The interval, as written
     */
    Optional<String> suspensionCheckInterval();

  }

}
