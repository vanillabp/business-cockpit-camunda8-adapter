package io.vanillabp.cockpit.camunda8;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda8.deployment.Camunda8DeploymentService;
import io.vanillabp.cockpit.extension.config.ConfigurationKeyCheck;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The key of this half, as the cockpit's neutral half checks it. This half announces
 * <code>suspension-check-interval</code> through the service loader. The neutral half then lets
 * it pass at the adapter and refuses it below, with the words the VanillaBP core uses for a
 * misplaced key.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CockpitKeysTest {

  private static void check(
      final String... propertyNames) {

    ConfigurationKeyCheck
        .refuseKeysNobodyReads(
            List.of(propertyNames), adapterId -> Camunda8DeploymentService.ADAPTER_TYPE,
            ConfigurationKeyCheck.keysOfTheBpmsHalves());

  }

  @Test
  @DisplayName("The service loader finds the announcement of this half")
  public void theAnnouncementIsFound() {

    assertTrue(
        ConfigurationKeyCheck
            .keysOfTheBpmsHalves()
            .stream()
            .anyMatch(Camunda8CockpitKeys.class::isInstance));

  }

  @Test
  @DisplayName("The interval at the adapter passes")
  public void theIntervalAtTheAdapterPasses() {

    assertDoesNotThrow(() -> check(Camunda8SuspensionCheckInterval.keyOf("c8")));

  }

  @Test
  @DisplayName("The interval at a workflow module or a workflow ends the start and names the key at the adapter")
  public void theIntervalBelowTheAdapterIsRefused() {

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> check(
            "vanillabp.workflow-modules.loans.adapters.c8.cockpit.suspension-check-interval",
            "vanillabp.workflow-modules.loans.workflows.Loan.adapters.c8.cockpit.suspension-check-interval"));

    final var message = refused.getMessage();
    assertTrue(
        message
            .contains("The Business Cockpit adapter for 'camunda8' reads 'suspension-check-interval' at the adapter"),
        message);
    assertTrue(message.contains("vanillabp.workflow-modules.loans.adapters.c8.cockpit.suspension-check-interval"),
        message);
    assertTrue(
        message
            .contains("vanillabp.workflow-modules.loans.workflows.Loan.adapters.c8.cockpit.suspension-check-interval"),
        message);
    assertTrue(message.contains(Camunda8SuspensionCheckInterval.keyOf("<adapter-id>")
        + ": PT1H"), message);

  }

  @Test
  @DisplayName("A typo of the interval names the key which was meant")
  public void aTypoNamesTheKeyMeant() {

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> check("vanillabp.adapters.c8.cockpit.suspension-check-intervall"));

    assertTrue(
        refused.getMessage().contains("Did you mean '%s'?".formatted(Camunda8SuspensionCheckInterval.keyOf("c8"))),
        refused.getMessage());

  }

}
