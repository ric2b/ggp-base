package org.ggp.base.player.gamer.statemachine.sancho;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Watchdog will reap the provided object if it hasn't shown signs of life.
 */
public class Watchdog implements Runnable
{
  private static final Logger LOGGER = LogManager.getLogger();

  /**
   * Interface implemented by objects which can be reaped by the watchdog.
   */
  public interface Reapable
  {
    /**
     * Reap the object.
     */
    public void reap();
  }

  private final Reapable mVictim;
  private final long mInterval;

  private volatile long mReapTime;

  private final Thread mThread;

  private volatile boolean mContinue;

  /**
   * Create a watchdog.
   *
   * @param xiInterval - the interval after which to reap a dead victim.
   * @param xiVictim - the victim.
   */
  public Watchdog(long xiInterval, Reapable xiVictim)
  {
    // Save off the provided values.
    mInterval = xiInterval;
    mVictim = xiVictim;

    // Get everything ready to start the watchdog.
    mContinue = true;
    alive();

    // Spawn the watchdog thread.
    mThread = new Thread(this, "Watchdog");
    mThread.setDaemon(true);
    mThread.start();
  }

  /**
   * Notify the watchdog that the victim is still alive.
   */
  public void alive()
  {
    long lReapInterval = System.currentTimeMillis() + mInterval;
    mReapTime = lReapInterval;
    LOGGER.debug("Watchdog won't fire until: " + mReapTime);
  }

  /**
   * Stop the watchdog.
   */
  public void stop()
  {
    LOGGER.info("Stop the watchdog");

    mContinue = false;
    mThread.interrupt();
    try
    {
      mThread.join(5000);
    }
    catch (InterruptedException lEx)
    {
      LOGGER.warn("Interrupted whilst waiting for watchdog to stop");
    }

    if (mThread.isAlive())
    {
      LOGGER.error("Failed to stop watchdog");
    }
    else
    {
      LOGGER.info("Watchdog terminated");
    }
  }

  @Override
  public void run()
  {
    LOGGER.info("Starting watchdog with interval " + mInterval + "ms");

    while (mContinue)
    {
      long lTimeRemaining = mReapTime - System.currentTimeMillis();

      if (lTimeRemaining > 0)
      {
        // Not time to reap yet.  Just wait.
        try
        {
          Thread.sleep(lTimeRemaining);
        }
        catch (InterruptedException lEx)
        {
          // We'll be interrupted when the game ends - to tidy up.  If this is a spurious wake-up, we'll notice and
          // go back to sleep.
        }
      }
      else
      {
        // The victim must be dead.  Reap it.
        LOGGER.warn("Watchdog liveness timer expired");
        mVictim.reap();
        return;
      }
    }

    LOGGER.info("Watchdog terminating by request");
  }
}
