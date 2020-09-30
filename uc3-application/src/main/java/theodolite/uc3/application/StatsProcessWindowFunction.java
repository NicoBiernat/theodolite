package theodolite.uc3.application;

import com.google.common.math.Stats;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

/**
 * This {@link ProcessWindowFunction} runs after the rolling aggregation for adding the key back to the result
 */
public class StatsProcessWindowFunction extends ProcessWindowFunction<Stats, Tuple2<String, Stats>, String, TimeWindow> {

  private static final long serialVersionUID = 4363099880614593379L;

  @Override
  public void process(String key, Context context, Iterable<Stats> elements, Collector<Tuple2<String, Stats>> out) {
    final Stats stats = elements.iterator().next();
    out.collect(new Tuple2<>(key, stats));
  }
}
