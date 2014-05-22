package org.ggp.base.player.gamer.statemachine.sancho;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

/**
 * Class for logging of system statistics.
 */
public class SystemStatsLogger implements Runnable
{
  private static final Logger STATS_LOGGER = LogManager.getLogger("stats");

  private static long                         INTERVAL    = 1000;
  private static MemoryMXBean                 MEMORY_BEAN = ManagementFactory.getMemoryMXBean();
  private static List<GarbageCollectorMXBean> GC_BEANS    = ManagementFactory.getGarbageCollectorMXBeans();

  private final Thread mThread;
  private final String mLogName;

  /**
   * Create a statistics logger.
   *
   * All statistics loggers MUST be stopped by calling {@link #stop()} to dispose of thread resources.
   *
   * @param xiLogName - the name of the log.
   */
  public SystemStatsLogger(String xiLogName)
  {
    mLogName = xiLogName;

    mThread = new Thread(this, "SystemStatsLogger");
    mThread.setDaemon(true);
    mThread.start();
  }

  @Override
  public void run()
  {
    ThreadContext.put("matchID", mLogName);

    long lNextLogTime = System.currentTimeMillis() + INTERVAL;
    boolean lInterrupted = false;
    while (!lInterrupted)
    {
      // Wait until the next time to make a stats log (or until we're interrupted).
      for (long lNow = System.currentTimeMillis();
           lNow < lNextLogTime;
           lNow = System.currentTimeMillis())
      {
        try
        {
          Thread.sleep(lNextLogTime - lNow);
        }
        catch (InterruptedException lEx)
        {
          lInterrupted = true;
          break;
        }
      }

      // Make stats logs
      makeStatsLog();

      // Set the next stats log time - based on the last time, not the time now.
      lNextLogTime += INTERVAL;
    }
  }

  /**
   * Dump system statistics.
   */
  private void makeStatsLog()
  {
    String lNow = "" + System.currentTimeMillis();
    MemoryUsage lMemUsage = MEMORY_BEAN.getHeapMemoryUsage();

    StringBuffer lLogBuf = new StringBuffer(1024);
    appendStatistic(lLogBuf, lNow, "Mem.Init", lMemUsage.getInit());
    appendStatistic(lLogBuf, lNow, "Mem.Used", lMemUsage.getUsed());
    appendStatistic(lLogBuf, lNow, "Mem.Committed", lMemUsage.getCommitted());
    appendStatistic(lLogBuf, lNow, "Mem.Max", lMemUsage.getMax());

    for (GarbageCollectorMXBean lGCBean : GC_BEANS)
    {
      appendStatistic(lLogBuf, lNow, "GC." + lGCBean.getName() + ".Count", lGCBean.getCollectionCount());
      appendStatistic(lLogBuf, lNow, "GC." + lGCBean.getName() + ".Time", lGCBean.getCollectionTime());
    }

    STATS_LOGGER.info(lLogBuf.toString());
  }

  private void appendStatistic(StringBuffer xiBuffer, String xiTime, String xiName, long xiValue)
  {
    xiBuffer.append(xiTime);
    xiBuffer.append(',');
    xiBuffer.append(xiName);
    xiBuffer.append(',');
    xiBuffer.append(xiValue);
    xiBuffer.append('\n');
  }

  /**
   * Stop the statistics logger.  After stopping, the statistics logger cannot be restarted.
   */
  public void stop()
  {
    mThread.interrupt();
    try
    {
      mThread.join(5000);
    }
    catch (InterruptedException lEx)
    {
      STATS_LOGGER.warn("Interrupted whilst waiting for StatsLogger to stop");
    }

    if (mThread.isAlive())
    {
      STATS_LOGGER.error("Failed to stop stats logging thread");
    }
  }
}
