package theodolite.commons.flink.serialization;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.google.common.math.Stats;

import java.io.Serializable;

/**
 * Kryo serializer and deserializer for the {@link Stats}.
 * Used for Flink internal serialization to improve performance by uc3 and uc4.
 */
public class StatsSerializer extends Serializer<Stats> implements Serializable {

  private static final long serialVersionUID = -1276866176534267373L;

  @Override
  public void write(final Kryo kryo, final Output output, final Stats object) {
    final byte[] data = object.toByteArray();
    output.writeInt(data.length);
    output.writeBytes(data);
  }

  @Override
  public Stats read(final Kryo kryo, final Input input, final Class<Stats> type) {
    final int numBytes = input.readInt();
    return Stats.fromByteArray(input.readBytes(numBytes));
  }
}
