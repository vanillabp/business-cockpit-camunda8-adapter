package io.vanillabp.cockpit.camunda8;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads <code>vanillabp.adapters.&lt;id&gt;.cockpit.suspension-check-interval</code>, the one
 * configuration key of this extension. Both platform modules hand the raw text here, so a value
 * which is no span of time gets the same message on Spring Boot and on Quarkus.
 * <p>
 * The key belongs to the section of a Camunda 8 adapter id, because the check runs once per
 * cluster. A <code>cockpit</code> section under an adapter id of another type is read by
 * nobody, and the start ends with a message which says so.
 */
public final class Camunda8SuspensionCheckInterval {

  /** How often a cluster is asked when the application says nothing. */
  public static final Duration DEFAULT = Duration.ofHours(1);

  private Camunda8SuspensionCheckInterval() {
  }

  /**
   * The key of one adapter id.
   *
   * @param adapterId The configured adapter id
   * @return The full key, for messages
   */
  public static String keyOf(
      final String adapterId) {

    return "vanillabp.adapters.%s.cockpit.%s".formatted(adapterId, Camunda8CockpitKeys.SUSPENSION_CHECK_INTERVAL);

  }

  /**
   * The settings of this extension, read from what the application configured. Both platform
   * modules bind the key and hand the raw text here.
   *
   * @param configured The raw value by adapter id, for every adapter id which carries the key
   * @param camunda8AdapterIds The configured adapter ids of type <code>camunda8</code>
   * @return How often each cluster is asked, {@link #DEFAULT} where nothing is configured
   * @throws IllegalStateException Where a value is no span of time, is negative, or stands at an
   *           adapter id which is no Camunda 8 adapter
   */
  public static Camunda8CockpitSettings settingsOf(
      final Map<String, String> configured,
      final Collection<String> camunda8AdapterIds) {

    final var intervals = intervalsOf(configured, camunda8AdapterIds);
    return adapterId -> intervals.getOrDefault(adapterId, DEFAULT);

  }

  /**
   * Reads the intervals an application configured.
   *
   * @param configured The raw value by adapter id, for every adapter id which carries the key
   * @param camunda8AdapterIds The configured adapter ids of type <code>camunda8</code>
   * @return The interval by adapter id, for those which carry the key.
   *         {@link Duration#ZERO} means switched off
   * @throws IllegalStateException Where a value is no span of time, is negative, or stands at an
   *           adapter id which is no Camunda 8 adapter. The message names every such key
   */
  public static Map<String, Duration> intervalsOf(
      final Map<String, String> configured,
      final Collection<String> camunda8AdapterIds) {

    final var intervals = new LinkedHashMap<String, Duration>();
    final var defects = new ArrayList<String>();
    new TreeMap<>(configured)
        .forEach((
            adapterId,
            value) -> {
          if ((value == null) || value.isBlank()) {
            return;
          }
          if (!camunda8AdapterIds.contains(adapterId)) {
            defects
                .add(
                    """
                          - '%s' stands at an adapter id which is no Camunda 8 adapter, and nobody reads it \
                        there. The Business Cockpit asks only a Camunda 8 cluster about suspended workflows. \
                        Move it to one of: %s."""
                        .formatted(keyOf(adapterId), String.join(", ", camunda8AdapterIds.stream().sorted().toList())));
            return;
          }
          try {
            final var interval = parse(value.trim());
            if (interval.isNegative()) {
              defects
                  .add(
                      "  - '%s' is '%s'. Write a span of time greater than zero, or 0 to switch the check off."
                          .formatted(keyOf(adapterId), value));
              return;
            }
            intervals.put(adapterId, interval);
          } catch (final RuntimeException e) {
            defects
                .add(
                    """
                          - '%s' is '%s', which is no span of time. Write it as ISO-8601 ('PT1H') or with a \
                        unit ('30m', '1h', '90s'), or 0 to switch the check off."""
                        .formatted(keyOf(adapterId), value));
          }
        });
    if (!defects.isEmpty()) {
      throw new IllegalStateException(
          """
              The Business Cockpit adapter for Camunda 8 cannot read how often it checks for suspended workflows:
              %s"""
              .formatted(String.join("\n", defects)));
    }
    return intervals;

  }

  /**
   * A span of time as both platforms let one be written: ISO-8601 (<code>PT1H</code>), a number
   * with a unit (<code>30m</code>, <code>1h</code>) or a plain number of milliseconds.
   */
  private static Duration parse(
      final String value) {

    final var lower = value.toLowerCase(Locale.ROOT);
    if (lower.startsWith("p") || lower.startsWith("-p")) {
      return Duration.parse(lower);
    }
    for (final var unit : new String[]{
        "ms", "s", "m", "h", "d"
    }) {
      if (lower.endsWith(unit)) {
        final var amount = Long.parseLong(lower.substring(0, lower.length() - unit.length()).trim());
        return switch (unit) {
          case "ms" -> Duration.ofMillis(amount);
          case "s" -> Duration.ofSeconds(amount);
          case "m" -> Duration.ofMinutes(amount);
          case "h" -> Duration.ofHours(amount);
          default -> Duration.ofDays(amount);
        };
      }
    }
    return Duration.ofMillis(Long.parseLong(lower));

  }

}
