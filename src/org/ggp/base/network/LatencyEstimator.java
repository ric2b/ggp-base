package org.ggp.base.network;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;

/**
 * Latency estimator.
 *
 * Performs TCP connection attempts to a target to estimate latency.
 *
 * Throughout this code, all times & intervals are measured in milliseconds.
 */
public class LatencyEstimator extends Thread
{
  // Maximum time that we'll wait for a ping response.  This should be a
  // back-stop.  We really don't want to hit this, because it'll mess up our
  // calculations.
  //
  // If it takes 10 seconds to do a ping, we're probably stuffed anyway,
  // because we'll get almost no thinking time.
  private static final int MAX_PING_TIME_MS = 10000;

  // Minimum inter-ping time.
  private static final int INTER_PING_INTERVAL_MS = 500;

  // Default latency to report, as a fallback if we're unable to get estimates
  private static final int DEFAULT_LATENCY_MS = 3000;

  // Exponential moving average, aging constant
  private static final double ALPHA = 0.9;

  // Address (including port) to ping.
  private final SocketAddress mTarget;

  // Current exponential moving average of the ping times, in milliseconds.
  private long mMovingAverage = DEFAULT_LATENCY_MS;

  // The number of times that we'll attempt to ping this target before giving
  // up completely.
  private int mRemainingAttempts = 3;

  // Thread control - for signalling termination.
  private volatile boolean mStop = false;

  /**
   * Create a latency probe for the specified target.
   *
   * @param xiTarget - the probe target.
   */
  public LatencyEstimator(SocketAddress xiTarget)
  {
    mTarget = xiTarget;

    System.out.println("Creating LatencyEstimator for " + xiTarget);

    // Make this thread a daemon thread and set its name.  Also set to the
    // highest priority because we're very latency sensitive.
    setName("Latency Estimator");
    setDaemon(true);
    setPriority(MAX_PRIORITY);
  }

  /**
   * @return the current latency estimate, in milliseconds.
   */
  public long getLatencyEstimate()
  {
    return (long)(mMovingAverage / 1000.0);
  }

  // !! ARR Also care about stddev + high-water mark?

  public void terminate()
  {
    mStop = true;
    interrupt();
  }

  @Override
  public void run()
  {
    // Keep running until told to stop.
    while (!mStop)
    {
      long lStartTime;
      long lEndTime;

      long lInterval;
      Socket socket = new Socket();
      try
      {
        socket.setTcpNoDelay(true);
      }
      catch (SocketException e)
      {
        System.err.println("Couldn't disable Nagle => increased latency");
        // !! ARR We should disable Nagle on the real sockets too
      }

      // Record the start time (in milliseconds, but using the nanosecond
      // timer because the millisecond timer doesn't have millisecond
      // accuracy).
      lStartTime = System.nanoTime() / 1000000;
      System.out.println("LatencyEstimator: Sending ping at " + lStartTime);

      // Attempt to connect to the remote party.  We don't mind whether this
      // succeeds or fails.
      try
      {
        // Connect to the remote system.
        socket.connect(mTarget, MAX_PING_TIME_MS);

        // Record the end time.
        lEndTime = System.nanoTime() / 1000000;
        System.out.println("LatencyEstimator: Response(+) at " + lEndTime);

        // Close the socket.
        socket.close();

        // We've had at least this response within the timeout - require
        // several consecutive failures before giving up.
        mRemainingAttempts = 6;
      }
      catch (SocketTimeoutException lEx)
      {
        lEndTime = System.nanoTime() / 1000000;
        System.out.println("LatencyEstimator: Timed out at " + lEndTime);

        // Timed out.  We use a very long timeout, so this probably indicates
        // that the server isn't sending TCP RSTs.  That's a bit sad because
        // it means these tests are useless.
        mRemainingAttempts--;
        if (mRemainingAttempts == 0)
        {
          System.err.println("Target unresponsive - giving up latency estimates");
          mStop = true;
          interrupt();
        }
      }
      catch (IOException lEx)
      {
        lEndTime = System.nanoTime() / 1000000;
        System.out.println("LatencyEstimator: Response(-) at " + lEndTime);
      }

      // Calculate the interval.
      lInterval = lEndTime - lStartTime;
      System.out.println("Interval = " + lInterval);

      // Blend the current result into the exponential moving average.  See
      // http://en.wikipedia.org/wiki/Moving_average#Exponential_moving_average
      mMovingAverage = mMovingAverage + (long)(ALPHA * (lInterval - mMovingAverage));

      System.out.println("LatencyEstimator: RTT=" + lInterval + "ms, EMA=" + mMovingAverage + "ms");

      // Sleep before taking the next sample (unless we're already overdue).
      long lSleepTime = INTER_PING_INTERVAL_MS - lInterval;
      if (lSleepTime > 0)
      {
        try
        {
          Thread.sleep(INTER_PING_INTERVAL_MS);
        }
        catch (InterruptedException ie)
        {
          // We've been interrupted - almost certainly to make us stop.
          // That's fine.
        }
      }
    }
  }
}