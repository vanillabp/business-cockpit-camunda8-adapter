package io.vanillabp.cockpit.camunda8.quarkus.it;

import io.camunda.client.CamundaClient;

/**
 * How a test suspends and resumes a process instance on this release line. This is the 8.8
 * variant. A cluster of this line cannot suspend an instance, and its client has no such command.
 * So a test which needs it skips itself.
 */
final class SuspensionOfTheLine {

  private SuspensionOfTheLine() {
  }

  /**
   * @return <code>false</code>: a cluster of this line cannot suspend an instance
   */
  static boolean theClusterCanSuspend() {

    return false;

  }

  /**
   * Never called on this line.
   *
   * @param client Not used
   * @param processInstanceKey Not used
   */
  static void suspend(
      final CamundaClient client,
      final long processInstanceKey) {

    throw new UnsupportedOperationException("a cluster of line 8.8 cannot suspend an instance");

  }

  /**
   * Never called on this line.
   *
   * @param client Not used
   * @param processInstanceKey Not used
   */
  static void resume(
      final CamundaClient client,
      final long processInstanceKey) {

    throw new UnsupportedOperationException("a cluster of line 8.8 cannot resume an instance");

  }

}
