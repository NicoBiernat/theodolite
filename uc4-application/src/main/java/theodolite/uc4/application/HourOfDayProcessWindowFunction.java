package theodolite.uc4.application;

import com.google.common.math.Stats;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import theodolite.uc4.application.util.HourOfDayKey;

/**
 * This {@link ProcessWindowFunction} runs after the rolling aggregation for adding the key back to the result
 */
public class HourOfDayProcessWindowFunction extends ProcessWindowFunction<Stats, Tuple2<HourOfDayKey, Stats>, HourOfDayKey, TimeWindow> {

  @Override
  public void process(final HourOfDayKey hourOfDayKey,
                      final Context context,
                      final Iterable<Stats> elements,
                      final Collector<Tuple2<HourOfDayKey, Stats>> out) {
    final Stats stats = elements.iterator().next();
    out.collect(new Tuple2<>(hourOfDayKey, stats));
  }

}