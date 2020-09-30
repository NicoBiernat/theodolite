package theodolite.commons.flink.serialization;

import java.io.Serializable;
import java.util.function.Supplier;

/**
 * For the {@link FlinkKafkaKeyValueSerde} we need to use an interface as a type
 * that combines the {@link Supplier} and {@link Serializable} interfaces.
 * @param <T>
 *   the type of the supplied value
 */
public interface SerializableSupplier<T> extends Supplier<T>, Serializable {
  // here be dragons
}
