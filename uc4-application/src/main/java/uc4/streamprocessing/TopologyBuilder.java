package uc4.streamprocessing;

import com.google.common.math.Stats;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import titan.ccp.common.kieker.kafka.IMonitoringRecordSerde;
import titan.ccp.model.records.ActivePowerRecord;
import titan.ccp.model.records.HourOfDayActivePowerRecord;
import titan.ccp.models.records.ActivePowerRecordFactory;
import uc4.streamprocessing.util.StatsFactory;

/**
 * Builds Kafka Stream Topology for the History microservice.
 */
public class TopologyBuilder {

  private static final Logger LOGGER = LoggerFactory.getLogger(TopologyBuilder.class);

  private final ZoneId zone = ZoneId.of("Europe/Paris"); // TODO as parameter


  private final String inputTopic;
  private final String outputTopic;
  private final Serdes serdes;

  private final StreamsBuilder builder = new StreamsBuilder();

  /**
   * Create a new {@link TopologyBuilder} using the given topics.
   */
  public TopologyBuilder(final String inputTopic, final String outputTopic,
      final Duration duration, final String schemaRegistryUrl) {
    this.inputTopic = inputTopic;
    this.outputTopic = outputTopic;
    this.serdes = new Serdes(schemaRegistryUrl);
  }

  /**
   * Build the {@link Topology} for the History microservice.
   */
  public Topology build() {

    final StatsKeyFactory<HourOfDayKey> keyFactory = new HourOfDayKeyFactory();
    final Serde<HourOfDayKey> keySerde = HourOfDayKeySerde.create();
    final StatsRecordFactory<HourOfDayKey, HourOfDayActivePowerRecord> statsRecordFactory =
        new HourOfDayRecordFactory();
    final TimeWindows timeWindows =
        TimeWindows.of(Duration.ofDays(30)).advanceBy(Duration.ofDays(1));
    final String statsTopic = "output";

    this.builder
        .stream(this.inputTopic,
            Consumed.with(this.serdes.string(),
                IMonitoringRecordSerde.serde(new ActivePowerRecordFactory())))
        .mapValues(kieker -> new ActivePowerRecord(
            kieker.getIdentifier(),
            kieker.getTimestamp(),
            kieker.getValueInW()))
        .selectKey((key, value) -> {
          final Instant instant = Instant.ofEpochMilli(value.getTimestamp());
          final LocalDateTime dateTime = LocalDateTime.ofInstant(instant, this.zone);
          return keyFactory.createKey(value.getIdentifier(), dateTime);
        })
        .groupByKey(Grouped.with(keySerde, this.serdes.activePowerRecordValues()))
        .windowedBy(timeWindows)
        .aggregate(
            () -> Stats.of(),
            (k, record, stats) -> StatsFactory.accumulate(stats, record.getValueInW()),
            Materialized.with(keySerde, this.serdes.stats()))
        .toStream()
        .map((key, stats) -> KeyValue.pair(
            keyFactory.getSensorId(key.key()),
            stats.toString()))
        // TODO
        // statsRecordFactory.create(key, value)))
        // .peek((k, v) -> LOGGER.info("{}: {}", k, v)) // TODO Temp logging
        .to(
            statsTopic,
            Produced.with(
                this.serdes.string(),
                this.serdes.string()));
    // this.serdes.avroValues()));

    return this.builder.build();
  }
}
