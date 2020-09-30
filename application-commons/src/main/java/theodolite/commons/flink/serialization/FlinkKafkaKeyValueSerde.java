package theodolite.commons.flink.serialization;

import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.connectors.kafka.KafkaDeserializationSchema;
import org.apache.flink.streaming.connectors.kafka.KafkaSerializationSchema;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.Serde;

import javax.annotation.Nullable;

/**
 * This combined serializer and deserializer is specially for reading and writing to/from Kafka.
 * It has the ability to read and write the key and timestamp of a record in addition to the value
 * itself.
 * Internally it wraps existing Serializer/Deserializer for both the key and the value.
 * @param <K>
 *   the type of the key
 * @param <V>
 *   the type of the value
 */
public class FlinkKafkaKeyValueSerde<K, V>
    implements KafkaDeserializationSchema<Tuple2<K, V>>,
               KafkaSerializationSchema<Tuple2<K, V>> {

  private static final long serialVersionUID = 2469569396501933443L;

  /** internal key serde */
  private transient Serde<K> keySerde;
  /** internal value serde */
  private transient Serde<V> valueSerde;

  /** serializable supplier for the key serde */
  private SerializableSupplier<Serde<K>> keySerdeSupplier;
  /** serializable supplier for the value serde */
  private SerializableSupplier<Serde<V>> valueSerdeSupplier;

  /** the Kafka topic that the serde serializes/deserializes to/from */
  private String topic;

  /** type info for Flinks type system */
  private TypeInformation<Tuple2<K,V>> typeInfo;

  /**
   * Creates a new FlinkKafkaKeyValueSerde.
   * @param topic
   *  The kafka topic that this serde should target
   * @param keySerdeSupplier
   *  A serializable supplier which returns the key serde
   * @param valueSerdeSupplier
   *  A serializable supplier which returns the value serde
   * @param typeInfo
   *  A Flink type information about the key value tuple
   */
  public FlinkKafkaKeyValueSerde(final String topic,
                                 final SerializableSupplier<Serde<K>> keySerdeSupplier,
                                 final SerializableSupplier<Serde<V>> valueSerdeSupplier,
                                 final TypeInformation<Tuple2<K, V>> typeInfo) {
    this.topic = topic;
    this.typeInfo = typeInfo;
    this.keySerdeSupplier = keySerdeSupplier;
    this.valueSerdeSupplier = valueSerdeSupplier;
  }

  @Override
  public boolean isEndOfStream(final Tuple2<K, V> nextElement) {
    return false; // unbounded stream
  }

  @Override
  public Tuple2<K, V> deserialize(final ConsumerRecord<byte[], byte[]> record) {
    ensureInitialized();
    final K key = this.keySerde.deserializer().deserialize(this.topic, record.key());
    final V value = this.valueSerde.deserializer().deserialize(this.topic, record.value());
    return new Tuple2<>(key, value);
  }

  @Override
  public TypeInformation<Tuple2<K, V>> getProducedType() {
    return this.typeInfo;
  }

  @Override
  public ProducerRecord<byte[], byte[]> serialize(Tuple2<K, V> element, @Nullable Long timestamp) {
    ensureInitialized();
    final byte[] key = this.keySerde.serializer().serialize(this.topic, element.f0);
    final byte[] value = this.valueSerde.serializer().serialize(this.topic, element.f1);
    return new ProducerRecord<>(this.topic, key, value);
  }

  private void ensureInitialized() {
    if (this.keySerde == null || this.valueSerde == null) {
      this.keySerde = this.keySerdeSupplier.get();
      this.valueSerde = this.valueSerdeSupplier.get();;
    }
  }
}
