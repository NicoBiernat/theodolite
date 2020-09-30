package theodolite.uc2.application;

import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.util.Collector;
import titan.ccp.model.sensorregistry.AggregatedSensor;
import titan.ccp.model.sensorregistry.Sensor;
import titan.ccp.model.sensorregistry.SensorRegistry;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Transforms a {@link SensorRegistry} into key value pairs of Sensor identifiers and their parents'
 * sensor identifiers. All pairs whose sensor's parents have changed since last iteration are
 * forwarded. A mapping of an identifier to <code>null</code> means that the corresponding sensor
 * does not longer exists in the sensor registry.
 */
public class ChildParentsFlatMapFunction extends RichFlatMapFunction<SensorRegistry, Tuple2<String, Set<String>>> {

  private static final long serialVersionUID = 3969444219510915221L;

  /**
   * Operator map state: child sensor -> set of parent sensors
   */
  private transient MapState<String, Set<String>> state;

  /**
   * Sets up the operator state.
   * @param parameters
   *  additional configuration (unused)
   */
  @Override
  public void open(Configuration parameters) {
    MapStateDescriptor<String, Set<String>> descriptor =
        new MapStateDescriptor<String, Set<String>>(
            "child-parents-state",
            TypeInformation.of(new TypeHint<String>(){}),
            TypeInformation.of(new TypeHint<Set<String>>(){}));
    this.state = getRuntimeContext().getMapState(descriptor);
  }

  /**
   * The FlatMap functions implementation
   * @param value
   *  the sensor registry
   * @param out
   *  the collector for emitting records
   * @throws Exception
   */
  @Override
  public void flatMap(SensorRegistry value, Collector<Tuple2<String, Set<String>>> out)
      throws Exception {
    final Map<String, Set<String>> childParentsPairs = this.constructChildParentsPairs(value);
    this.updateChildParentsPairs(childParentsPairs);
    this.updateState(childParentsPairs);
    childParentsPairs
        .entrySet()
        .stream()
        .map(e -> new Tuple2<>(e.getKey(), e.getValue()))
        .forEach(out::collect);
  }

  /**
   * Creates pairs of a child sensor with all of its parent sensors from a sensor registry.
   * @param registry
   *   the sensor registry
   * @return
   *  a map of sensor to a set of its parent sensors
   */
  private Map<String, Set<String>> constructChildParentsPairs(final SensorRegistry registry) {
    return this.streamAllChildren(registry.getTopLevelSensor())
        .collect(Collectors.toMap(
            Sensor::getIdentifier,
            child -> child.getParent()
                .map(p -> Set.of(p.getIdentifier()))
                .orElseGet(Set::of)));
  }

  /**
   * Returns a stream of all children sensors from an aggregated sensor recursively.
   * @param sensor
   *  the aggregated sensor
   * @return
   *  the stream of all children sensors
   */
  private Stream<Sensor> streamAllChildren(final AggregatedSensor sensor) {
    return sensor.getChildren().stream()
        .flatMap(s -> Stream.concat(
            Stream.of(s),
            s instanceof AggregatedSensor ? this.streamAllChildren((AggregatedSensor) s)
                : Stream.empty()));
  }

  /**
   * Updates a map of sensor to a set of parent sensors
   * by using the operator state to identify changes to the configuration.
   * @param childParentsPairs
   *  the map of sensor to a set of parent sensors
   * @throws Exception
   */
  private void updateChildParentsPairs(final Map<String, Set<String>> childParentsPairs)
      throws Exception {
    final Iterator<Map.Entry<String, Set<String>>> oldChildParentsPairs = this.state.iterator();
    while (oldChildParentsPairs.hasNext()) {
      final Map.Entry<String, Set<String>> oldChildParentPair = oldChildParentsPairs.next();
      final String identifier = oldChildParentPair.getKey();
      final Set<String> oldParents = oldChildParentPair.getValue();
      final Set<String> newParents = childParentsPairs.get(identifier); // null if not exists
      if (newParents == null) {
        // Sensor was deleted
        childParentsPairs.put(identifier, null);
      } else if (newParents.equals(oldParents)) {
        // No changes
        childParentsPairs.remove(identifier);
      }
      // Else: Later Perhaps: Mark changed parents
    }
  }

  /**
   * Updates the operator map state state with a new map
   * @param childParentsPairs
   *  the new map
   * @throws Exception
   */
  private void updateState(final Map<String, Set<String>> childParentsPairs) throws Exception {
    for (final Map.Entry<String, Set<String>> childParentPair : childParentsPairs.entrySet()) {
      if (childParentPair.getValue() == null) {
        this.state.remove(childParentPair.getKey());
      } else {
        this.state.put(childParentPair.getKey(), childParentPair.getValue());
      }
    }
  }
}
