package io.vanillabp.cockpit.camunda8.springboot.test;

import io.camunda.client.CamundaClient;

/**
 * How a test suspends and resumes a process instance on this release line. This is the 8.10
 * variant. A cluster of this line can do both, over REST, and the commands exist only on the
 * client of this line.
 */
final class SuspensionOfTheLine {

  private SuspensionOfTheLine() {
  }

  /**
   * @return <code>true</code>: a cluster of this line can suspend an instance
   */
  static boolean theClusterCanSuspend() {

    return true;

  }

  /**
   * Suspends an instance, the way an operator does it in Operate.
   *
   * @param client The client of the cluster
   * @param processInstanceKey The instance
   */
  static void suspend(
      final CamundaClient client,
      final long processInstanceKey) {

    client.newSuspendProcessInstanceCommand(processInstanceKey).send().join();

  }

  /**
   * Resumes an instance.
   *
   * @param client The client of the cluster
   * @param processInstanceKey The instance
   */
  static void resume(
      final CamundaClient client,
      final long processInstanceKey) {

    client.newResumeProcessInstanceCommand(processInstanceKey).send().join();

  }

}
