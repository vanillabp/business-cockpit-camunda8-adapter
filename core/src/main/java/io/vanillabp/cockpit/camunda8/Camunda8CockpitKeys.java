package io.vanillabp.cockpit.camunda8;

import java.util.List;

import io.vanillabp.camunda8.deployment.Camunda8DeploymentService;
import io.vanillabp.cockpit.extension.spi.AdapterCockpitKey;
import io.vanillabp.cockpit.extension.spi.AdapterCockpitKeys;

/**
 * Tells the Business Cockpit's neutral half which key this half reads below the adapter ids of
 * Camunda 8, and at which level. With it, the neutral half refuses the key at a workflow module, a
 * workflow or a task, and a typo of it, on both platforms. The service loader finds this class
 * through the file below <code>META-INF/services</code> of this jar.
 */
public class Camunda8CockpitKeys implements AdapterCockpitKeys {

  /** The key below <code>vanillabp.adapters.&lt;adapter-id&gt;.cockpit</code>. */
  public static final String SUSPENSION_CHECK_INTERVAL = "suspension-check-interval";

  /**
   * The service loader builds this class. It holds no state.
   */
  public Camunda8CockpitKeys() {
  }

  @Override
  public String adapterType() {

    return Camunda8DeploymentService.ADAPTER_TYPE;

  }

  @Override
  public List<AdapterCockpitKey> keys() {

    return List
        .of(
            AdapterCockpitKey
                .atTheAdapterOnly(
                    SUSPENSION_CHECK_INTERVAL, "PT1H",
                    "The check asks the whole cluster at once, so it runs once for each adapter id and not for each workflow module."));

  }

}
