package theodolite.uc3.application;

import com.google.common.math.Stats;
import org.apache.commons.configuration2.Configuration;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.contrib.streaming.state.RocksDBStateBackend;
import org.apache.flink.runtime.state.filesystem.FsStateBackend;
import org.apache.flink.runtime.state.memory.MemoryStateBackend;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer;
import org.apache.kafka.common.serialization.Serdes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import theodolite.commons.flink.serialization.FlinkKafkaKeyValueSerde;
import theodolite.commons.flink.serialization.FlinkMonitoringRecordSerde;
import theodolite.commons.flink.serialization.StatsSerializer;
import titan.ccp.common.configuration.Configurations;
import titan.ccp.models.records.ActivePowerRecord;
import titan.ccp.models.records.ActivePowerRecordFactory;

import java.io.IOException;
import java.util.Properties;


/**
 * The History Microservice Flink Job (modified for downsampling).
 * This job downsamples incoming sensor records by aggregating them in consecutive time windows
 * and outputting one record per window.
 */
public class HistoryServiceFlinkJob {

  private static final Logger LOGGER = LoggerFactory.getLogger(HistoryServiceFlinkJob.class);

  private final Configuration config = Configurations.create();

  private void run() {
    // Configuration
    final String applicationName = this.config.getString(ConfigurationKeys.APPLICATION_NAME);
    final String applicationVersion = this.config.getString(ConfigurationKeys.APPLICATION_VERSION);
    final String applicationId = applicationName + "-" + applicationVersion;
    final int commitIntervalMs = this.config.getInt(ConfigurationKeys.COMMIT_INTERVAL_MS);
    final String kafkaBroker = this.config.getString(ConfigurationKeys.KAFKA_BOOTSTRAP_SERVERS);
    final String inputTopic = this.config.getString(ConfigurationKeys.KAFKA_INPUT_TOPIC);
    final String outputTopic = this.config.getString(ConfigurationKeys.KAFKA_OUTPUT_TOPIC);
    final int windowDuration = this.config.getInt(ConfigurationKeys.KAFKA_WINDOW_DURATION_MINUTES);
    final String stateBackend = this.config.getString(ConfigurationKeys.FLINK_STATE_BACKEND, "").toLowerCase();
    final String stateBackendPath = this.config.getString(ConfigurationKeys.FLINK_STATE_BACKEND_PATH, "file:///opt/flink/statebackend");
    final int memoryStateBackendSize = this.config.getInt(ConfigurationKeys.FLINK_STATE_BACKEND_MEMORY_SIZE, MemoryStateBackend.DEFAULT_MAX_STATE_SIZE);
    final boolean checkpointing= this.config.getBoolean(ConfigurationKeys.CHECKPOINTING, true);

    // Source setup
    final Properties kafkaProps = new Properties();
    kafkaProps.setProperty("bootstrap.servers", kafkaBroker);
    kafkaProps.setProperty("group.id", applicationId);

    final FlinkMonitoringRecordSerde<ActivePowerRecord, ActivePowerRecordFactory> sourceSerde =
        new FlinkMonitoringRecordSerde<>(
            inputTopic,
            ActivePowerRecord.class,
            ActivePowerRecordFactory.class);

    final FlinkKafkaConsumer<ActivePowerRecord> kafkaSource = new FlinkKafkaConsumer<>(
        inputTopic, sourceSerde, kafkaProps);

    kafkaSource.setStartFromGroupOffsets();
    if (checkpointing)
      kafkaSource.setCommitOffsetsOnCheckpoints(true);
    kafkaSource.assignTimestampsAndWatermarks(WatermarkStrategy.forMonotonousTimestamps());

    // Sink setup
    final FlinkKafkaKeyValueSerde<String, String> sinkSerde =
        new FlinkKafkaKeyValueSerde<>(outputTopic,
            Serdes::String,
            Serdes::String,
            TypeInformation.of(new TypeHint<Tuple2<String, String>>(){})
        );
    kafkaProps.setProperty("transaction.timeout.ms", ""+5*60*1000);
    final FlinkKafkaProducer<Tuple2<String, String>> kafkaSink = new FlinkKafkaProducer<>(
        outputTopic, sinkSerde, kafkaProps, FlinkKafkaProducer.Semantic.AT_LEAST_ONCE);
    kafkaSink.setWriteTimestampToKafka(true);

    // Get a StreamExecutionEnvironment
    final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
    if (checkpointing)
      env.enableCheckpointing(commitIntervalMs);

    // State Backend
    if (stateBackend.equals("filesystem")) {
      env.setStateBackend(new FsStateBackend(stateBackendPath));
    } else if (stateBackend.equals("rocksdb")) {
      try {
        env.setStateBackend(new RocksDBStateBackend(stateBackendPath, true));
      } catch (IOException e) {
        e.printStackTrace();
      }
    } else {
      env.setStateBackend(new MemoryStateBackend(memoryStateBackendSize));
    }

    // Register Kryo serializers
    env.getConfig().registerTypeWithKryoSerializer(ActivePowerRecord.class,
        new FlinkMonitoringRecordSerde<>(
            inputTopic,
            ActivePowerRecord.class,
            ActivePowerRecordFactory.class));
    env.getConfig().registerTypeWithKryoSerializer(Stats.class, new StatsSerializer());

    env.getConfig().getRegisteredTypesWithKryoSerializers().forEach((c, s) ->
        LOGGER.info("Class " + c.getName() + " registered with serializer "
            + s.getSerializer().getClass().getName()));

    // Streaming data flow
    final DataStream<ActivePowerRecord> stream = env.addSource(kafkaSource)
        .name("[Kafka Consumer] Topic: " + inputTopic);

    stream
        .rebalance()
        .keyBy((KeySelector<ActivePowerRecord, String>) ActivePowerRecord::getIdentifier)
        .window(TumblingEventTimeWindows.of(Time.minutes(windowDuration)))
        .aggregate(new StatsAggregateFunction(), new StatsProcessWindowFunction())
        .map(new MapFunction<Tuple2<String, Stats>, Tuple2<String, String>>() {
          @Override
          public Tuple2<String, String> map(Tuple2<String, Stats> t) {
            final String key = t.f0;
            final String value = t.f1.toString();
            LOGGER.info(key + ": " + value);
            return new Tuple2<>(key, value);
          }
        }).name("map")
        .addSink(kafkaSink).name("[Kafka Producer] Topic: " + outputTopic);

    // Execution plan
    if (LOGGER.isInfoEnabled()) {
      LOGGER.info("Execution Plan: " + env.getExecutionPlan());
    }

    // Execute the job
    try {
      env.execute(applicationId);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public static void main(final String[] args) {
    new HistoryServiceFlinkJob().run();
  }
}
