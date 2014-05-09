package org.ggp.base.player.gamer.statemachine.sancho;

import com.sun.jna.LastErrorException;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinDef.DWORD;

/**
 * Utility class for controlling threading behaviour, including processor affinity.
 *
 * There are two classes of CPU intensive thread for Sancho.
 *
 * - GameSearcher     - there's always a single instance of this thread.
 * - RolloutProcessor - there can be several of these.
 *
 * The game searcher always uses the first vCPU.  Rollout processors use the next <num rollout processors> vCPUs.
 * If this doesn't use all the vCPUs then the pattern repeats over the next higher set of vCPUs (to allow for
 * another instance to be running in the same process).
 *
 * Note that this class contains Windows-specific code.  It would be straightforward to extend to Linux and POSIX
 * systems if required.
 */
public class ThreadControl
{
  /**
   * The number of vCPUs available on the system.  (For a hyper-threaded system, each hyper-thread counts as a CPU.)
   */
  public static final int     NUM_CPUS              = Runtime.getRuntime().availableProcessors();

  /**
   * Whether to perform rollouts synchronously from the search thread.  Used to disable threading for debugging.
   */
  public static final boolean RUN_SYNCHRONOUSLY     = false;

  /**
   * System-specific parameter to reduce the system load.
   */
  public static final boolean HALF_STRENGTH         = false;

  /**
   * The number of CPU-intensive threads.
   *
   * Unless configured otherwise, use half the available vCPUs.
   *
   * !! ARR Put in a method to configure this.
   */
  public static final int     CPU_INTENSIVE_THREADS = RUN_SYNCHRONOUSLY ? 1 :
                                                                          HALF_STRENGTH ? ((((NUM_CPUS + 1) / 2) + 1) / 2) :
                                                                                          ((NUM_CPUS + 1) / 2);
  // public static final int     CPU_INTENSIVE_THREADS = NUM_CPUS; // !! ARR Hack

  /**
   * The number of rollout threads.
   */
  public static final int     ROLLOUT_THREADS       = CPU_INTENSIVE_THREADS - 1;

  /**
   * vCPU index of the last registered search thread.  (Wraps at NUM_CPUS.)
   */
  private static int sNextSearchThreadCPUIndex = 0;

  /**
   * vCPU index of the last registered rollout thread.  (Wraps at NUM_CPUS.)
   */
  private static int sNextRolloutThreadCPUIndex = 1;

  private ThreadControl()
  {
    // Private default constructor.
  }

  /**
   * Register a game search thread.
   */
  public static void registerSearchThread()
  {
    synchronized (ThreadControl.class)
    {
      // Bind this thread to the selected virtual CPU.
      // !! ARR Disabled: ThreadControl.setThreadAffinity(1 << sNextSearchThreadCPUIndex);
      System.out.println("  Bound search thread to vCPU:  " + sNextSearchThreadCPUIndex);

      // Calculate the next available virtual CPU for search threads.
      sNextSearchThreadCPUIndex = (sNextSearchThreadCPUIndex + CPU_INTENSIVE_THREADS) % NUM_CPUS;
    }
  }

  /**
   * Register a rollout thread.
   */
  public static void registerRolloutThread()
  {
    synchronized (ThreadControl.class)
    {
      // Bind this thread to the selected virtual CPU.
      // !! ARR Disabled: ThreadControl.setThreadAffinity(1 << sNextRolloutThreadCPUIndex);
      System.out.println("  Bound rollout processor to vCPU: " + sNextRolloutThreadCPUIndex);

      // Calculate the next available virtual CPU for rollout threads, wrapping as required and leaving a gap for the
      // search thread if required.
      sNextRolloutThreadCPUIndex++;
      sNextRolloutThreadCPUIndex = sNextRolloutThreadCPUIndex % NUM_CPUS;
      if ((sNextRolloutThreadCPUIndex % CPU_INTENSIVE_THREADS) == 0)
      {
        sNextRolloutThreadCPUIndex++;
      }
    }
  }

  /**
   * JNA access to kernel32.dll.
   */
  private interface Kernel32Library extends Library
  {
    public static final Kernel32Library INSTANCE = (Kernel32Library)Native.loadLibrary("kernel32", Kernel32Library.class);
    public void SetThreadAffinityMask(final int pid, final DWORD lpProcessAffinityMask) throws LastErrorException;
    public int GetCurrentThread() throws LastErrorException;
  }

  /**
   * Set CPU affinity for the current thread.
   *
   * @param xiAffinityMask - the affinity mask.
   */
  private static void setThreadAffinity(final long xiAffinityMask)
  {
    DWORD lAffinityMask = new DWORD(xiAffinityMask);
    try
    {
      Kernel32Library.INSTANCE.SetThreadAffinityMask(Kernel32Library.INSTANCE.GetCurrentThread(), lAffinityMask);
    }
    catch (LastErrorException lEx)
    {
      throw new IllegalStateException("Failed to set thread affinity: err=" + lEx.getErrorCode(), lEx);
    }
  }
}