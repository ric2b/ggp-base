package org.ggp.base.apps.utilities;

import java.util.concurrent.locks.LockSupport;

public class WaitTimings
{

  private static final long NANO_ITERATIONS =   10000000;
  private static final long NOOP_ITERATIONS = 1000000000;
  private static final int YIELD_ITERATIONS =   10000000;
  private static final int PARK_ITERATIONS  =       1000;

  public static void main(String[] args)
  {
    // JVM warm-up
    for (long ii = 0; ii < PARK_ITERATIONS; ii++)
    {
      System.nanoTime();
      Thread.yield();
      LockSupport.parkNanos(1);
    }
    System.out.println("Warmed up");

    // See how long timing takes.
    long lElapsed = 0;
    for (long ii = 0; ii < NANO_ITERATIONS; ii++)
    {
      lElapsed -= System.nanoTime();
      lElapsed += System.nanoTime();
    }
    System.out.println("Average System.nanoTime() time = " + lElapsed  / NANO_ITERATIONS + "ns");

    // See how long a no-op takes.
    long lStart = System.nanoTime();
    for (long ii = 0; ii < NOOP_ITERATIONS; ii++)
    {
      // Do nothing
    }
    lElapsed = System.nanoTime() - lStart;
    System.out.println("Average no-op time = " + lElapsed / NOOP_ITERATIONS + "ns");

    // See how long we yield for.
    lStart = System.nanoTime();
    for (int ii = 0; ii < YIELD_ITERATIONS; ii++)
    {
      Thread.yield();
    }
    lElapsed = System.nanoTime() - lStart;
    System.out.println("Average yield time = " + lElapsed / YIELD_ITERATIONS + "ns");

    // See how long we park for.
    lStart = System.nanoTime();
    for (int ii = 0; ii < PARK_ITERATIONS; ii++)
    {
      LockSupport.parkNanos(1);
    }
    lElapsed = System.nanoTime() - lStart;
    System.out.println("Average park time = " + lElapsed / PARK_ITERATIONS + "ns");
  }
}
