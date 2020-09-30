package theodolite.uc2.application.util;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import java.io.Serializable;
import java.util.Set;

/**
 * Kryo serializer and deserializer for a {@link Set}.
 * Used for Flink internal serialization to improve performance.
 */
public final class ImmutableSetSerializer extends Serializer<Set<Object>> implements Serializable {

  public ImmutableSetSerializer() {
    super(false, true);
  }

  @Override
  public void write(Kryo kryo, Output output, Set<Object> object) {
    output.writeInt(object.size(), true);
    for (final Object elm : object) {
      kryo.writeClassAndObject(output, elm);
    }
  }

  @Override
  public Set<Object> read(Kryo kryo, Input input, Class<Set<Object>> type) {
    final int size = input.readInt(true);
    final Object[] list = new Object[size];
    for (int i = 0; i < size; ++i) {
      list[i] = kryo.readClassAndObject(input);
    }
    return Set.of(list);
  }
}