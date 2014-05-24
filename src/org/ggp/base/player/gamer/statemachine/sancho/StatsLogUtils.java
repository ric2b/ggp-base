package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility methods for logging statistics.
 */
public class StatsLogUtils
{
  /**
   * Graph types in the visualisation.
   */
  public static enum Graph
  {
    /**
     * Memory usage.
     */
    MEM,

    /**
     * GC time.
     */
    GC,

    /**
     * Performance.
     */
    PERF;
  }

  /**
   * Statistics series for logging.
   */
  public static enum Series
  {
    /**
     * Currently used heap (bytes).
     */
    MEM_USED       (Graph.MEM,  0, "Used"),

    /**
     * Memory allocated from the O.S. for heap usage (bytes).
     */
    MEM_COMMITTED  (Graph.MEM,  0, "Committed"),

    /**
     * Maximum configured heap size (bytes).
     */
    MEM_MAX        (Graph.MEM,  0, "Max"),

    /**
     * Garbage collection time (ms).
     */
    GC_TIME        (Graph.GC,   0, "Time"),

    /**
     * Garbage collection count.
     */
    GC_COUNT       (Graph.GC,   1, "Count"),

    /**
     * Node expansions.
     */
    NODE_EXPANSIONS(Graph.PERF, 0, "Expansions"),

    /**
     * Depth charges.
     */
    DEPTH_CHARGES  (Graph.PERF, 1, "Depth charges");

    private final Graph  mGraph;
    private final int    mAxis;
    private final String mName;
    private final Vector<String> mXValues;
    private final Vector<String> mYValues;

    private static final Pattern LINE_PATTERN = Pattern.compile("^([^,]+),(\\d+),(\\d+)");

    private Series(Graph xiGraph, int xiAxis, String xiName)
    {
      mGraph   = xiGraph;
      mAxis    = xiAxis;
      mName    = xiName;
      mXValues = new Vector<>();
      mYValues = new Vector<>();
    }

    /**
     * Log a data point.
     *
     * @param xiBuffer - the buffer to append to.
     * @param xiTime   - the x-value (usually a time in ms).
     * @param xiValue  - the y-value.
     */
    public void logDataPoint(StringBuffer xiBuffer, long xiTime, long xiValue)
    {
      xiBuffer.append(this);
      xiBuffer.append(',');
      xiBuffer.append(xiTime);
      xiBuffer.append(',');
      xiBuffer.append(xiValue);
      xiBuffer.append('\n');
    }

    /**
     * Load a data point into this series from file.
     *
     * For use by the log summariser only.
     *
     * @param lLine - the line in the file.
     */
    public static void loadDataPoint(String lLine)
    {
      Matcher lMatcher = LINE_PATTERN.matcher(lLine);
      if (lMatcher.matches())
      {
        Series lSeries = Series.valueOf(lMatcher.group(1));
        lSeries.addDataPoint(lMatcher.group(2), lMatcher.group(3));
      }
    }

    private void addDataPoint(String xiXValue, String xiYValue)
    {
      assert(mXValues.size() == mYValues.size()) : mXValues.size() + " X-values but " + mYValues.size() + " Y-values";

      mXValues.add(xiXValue);
      mYValues.add(xiYValue);
    }

    /**
     * Append details of this Series to a JSON buffer.
     *
     * For use by the log summariser only.
     *
     * @param xiBuffer - the buffer.
     */
    public void appendToJSON(StringBuffer xiBuffer)
    {
      assert(mXValues.size() == mYValues.size()) : mXValues.size() + " X-values but " + mYValues.size() + " Y-values";

      xiBuffer.append("{\"showon\":\"");
      xiBuffer.append(mGraph);
      xiBuffer.append("\",\"type\":\"line\",\"name\":\"");
      xiBuffer.append(mName);
      xiBuffer.append("\",\"yAxis\":");
      xiBuffer.append(mAxis);
      xiBuffer.append(",\"data\":[");
      for (int lii = 0; lii < mXValues.size(); lii++)
      {
        xiBuffer.append('[');
        xiBuffer.append(mXValues.get(lii));
        xiBuffer.append(',');
        xiBuffer.append(mYValues.get(lii));
        xiBuffer.append(']');
        xiBuffer.append(',');
      }
      xiBuffer.setLength(xiBuffer.length() - 1);
      xiBuffer.append("]}");
    }

    /**
     * @return whether the series is empty.
     *
     * For use by the log summariser only.
     */
    public boolean isEmpty()
    {
      assert(mXValues.size() == mYValues.size()) : mXValues.size() + " X-values but " + mYValues.size() + " Y-values";

      return mXValues.isEmpty();
    }

    /**
     * Clear all data points from the series.
     *
     * For use by the log summariser only.
     */
    public void reset()
    {
      mXValues.clear();
      mYValues.clear();

      assert(mXValues.size() == 0) : "X-values not cleared";
      assert(mYValues.size() == 0) : "Y-values not cleared";
    }

  }
}
