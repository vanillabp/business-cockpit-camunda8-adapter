package io.vanillabp.cockpit.camunda8.springboot;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;

/**
 * The keys this extension reads itself, as an OVERLAY of the shared <code>vanillabp.*</code>
 * configuration tree on Spring Boot. It is one more {@code @ConfigurationProperties("vanillabp")}
 * class beside the platform's own and the Camunda 8 adapter's. Classes with the same prefix bind
 * side by side, and a key which one of them does not know is ignored by that one.
 * <p>
 * There is one key:
 *
 * <pre>
 * vanillabp.adapters.&lt;adapter-id&gt;.cockpit.suspension-check-interval
 * </pre>
 *
 * It is bound as text and read by {@code Camunda8SuspensionCheckInterval} in the core, so a value
 * which is no span of time gets the same message here and on Quarkus.
 */
@ConfigurationProperties(MigrationAdapterProperties.PREFIX)
public class Camunda8CockpitOverlayProperties {

  /** The sections of the adapter ids, by adapter id. */
  private Map<String, Adapter> adapters = Map.of();

  /**
   * Creates the tree, which Spring Boot fills when it binds the properties.
   */
  public Camunda8CockpitOverlayProperties() {
  }

  /**
   * Gives the sections of the adapter ids.
   *
   * @return The sections of the adapter ids, by adapter id
   */
  public Map<String, Adapter> getAdapters() {

    return adapters;

  }

  /**
   * Sets the sections of the adapter ids. Spring Boot calls it while it binds.
   *
   * @param adapters The sections of the adapter ids, by adapter id
   */
  public void setAdapters(
      final Map<String, Adapter> adapters) {

    this.adapters = adapters;

  }

  /**
   * What the application wrote for the check, by adapter id.
   *
   * @return The raw values, for the adapter ids which carry the key
   */
  public Map<String, String> suspensionCheckIntervals() {

    final var intervals = new LinkedHashMap<String, String>();
    adapters
        .forEach((
            adapterId,
            adapter) -> {
          if ((adapter != null) && (adapter.getCockpit() != null) && (adapter.getCockpit()
              .getSuspensionCheckInterval() != null)) {
            intervals.put(adapterId, adapter.getCockpit().getSuspensionCheckInterval());
          }
        });
    return intervals;

  }

  /**
   * The section of one adapter id, as far as the Business Cockpit reads it.
   */
  public static class Adapter {

    /** What the Business Cockpit reads for this adapter. */
    private Cockpit cockpit;

    /**
     * Creates the section, which Spring Boot fills.
     */
    public Adapter() {
    }

    /**
     * Gives the section <code>cockpit</code>.
     *
     * @return What the Business Cockpit reads for this adapter
     */
    public Cockpit getCockpit() {

      return cockpit;

    }

    /**
     * Sets the section <code>cockpit</code>. Spring Boot calls it while it binds.
     *
     * @param cockpit What the Business Cockpit reads for this adapter
     */
    public void setCockpit(
        final Cockpit cockpit) {

      this.cockpit = cockpit;

    }

  }

  /**
   * <code>vanillabp.adapters.&lt;adapter-id&gt;.cockpit</code>.
   */
  public static class Cockpit {

    /**
     * How often the Business Cockpit asks the cluster which workflows are suspended. 0 switches
     * the check off. The default is one hour.
     */
    private String suspensionCheckInterval;

    /**
     * Creates the section, which Spring Boot fills.
     */
    public Cockpit() {
    }

    /**
     * Gives the interval of the check.
     *
     * @return How often the cluster is asked, as written
     */
    public String getSuspensionCheckInterval() {

      return suspensionCheckInterval;

    }

    /**
     * Sets the interval of the check. Spring Boot calls it while it binds.
     *
     * @param suspensionCheckInterval How often the cluster is asked, as written
     */
    public void setSuspensionCheckInterval(
        final String suspensionCheckInterval) {

      this.suspensionCheckInterval = suspensionCheckInterval;

    }

  }

}
