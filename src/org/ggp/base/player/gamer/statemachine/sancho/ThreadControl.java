package org.ggp.base.player.gamer.statemachine.sancho;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.player.gamer.statemachine.sancho.MachineSpecificConfiguration.CfgItem;

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
 * These can be bound to vCPUs if desired because on some systems it increases performance.
 *
 * Note that this class contains Windows-specific code.  It would be straightforward to extend to Linux and POSIX
 * systems if required.
 */
public class ThreadControl
{
  private static final Logger LOGGER = LogManager.getLogger();

  /**
   * The number of vCPUs available on the system.  (For a hyper-threaded system, each hyper-thread counts as a CPU.)
   */
  public static final int NUM_CPUS = Runtime.getRuntime().availableProcessors();

  /**
   * Whether to perform rollouts synchronously from the search thread.  Used to disable threading for debugging.
   */
  public static final boolean RUN_SYNCHRONOUSLY = false;

  /**
   * System-specific parameter to reduce the system load.
   */
  public static final boolean HALF_STRENGTH = false;

  /**
   * The number of CPU-intensive threads.
   *
   * Unless configured otherwise, use half the available vCPUs.
   */
  public static final int CPU_INTENSIVE_THREADS = RUN_SYNCHRONOUSLY ?
               1 :
               MachineSpecificConfiguration.getCfgVal(CfgItem.CPU_INTENSIVE_THREADS,
                                                      HALF_STRENGTH ? ((((NUM_CPUS + 1) / 2) + 1) / 2) : ((NUM_CPUS + 1) / 2));

  /**
   * Whether to pin the CPU intensive threads to fixed cores to prevent core thrashing
   */
  private static final boolean USE_AFFINITY_MAPPING =
                                                     MachineSpecificConfiguration.getCfgVal(CfgItem.USE_AFFINITY, true);

  /**
   * On hyper-threaded CPUs we get far better performance allocating every other logical core
   * and so placing our threads on separate physical cores - always do this if we're only using half the
   * logical cores anyway
   */
  private static final int CPU_STRIPE_STRIDE = ((NUM_CPUS + 1) / 2 >= CPU_INTENSIVE_THREADS) ? 2 : 1;

  /**
   * The number of rollout threads.
   */
  public static final int ROLLOUT_THREADS = CPU_INTENSIVE_THREADS - 1;

  /**
   * A parity which influences whether logical CPU striping is done on odd or even
   * parity CPU ids
   */
  public static boolean CPUIdParity = false;

  /**
   * vCPU index of the last registered search thread.  (Wraps at NUM_CPUS.)
   */
  private static int sNextSearchThreadCPUIndex = 0;

  /**
   * vCPU index of the last registered rollout thread.  (Wraps at NUM_CPUS.)
   */
  private static int sNextRolloutThreadCPUIndex = CPU_STRIPE_STRIDE;

  private ThreadControl()
  {
    // Private default constructor.
  }

  /**
   * Reset the allocation cursors for CPU id to original values - should be used
   * when destroying the current consuming threads
   */
  public static void reset()
  {
    sNextSearchThreadCPUIndex = (CPUIdParity ? 1 : 0);
    sNextRolloutThreadCPUIndex = (CPU_STRIPE_STRIDE + (CPUIdParity ? 1 : 0)) % NUM_CPUS;
  }

  /**
   * Register a game search thread.
   */
  public static void registerSearchThread()
  {
    if (USE_AFFINITY_MAPPING)
    {
      synchronized (ThreadControl.class)
      {
        // Bind this thread to the selected virtual CPU.
        ThreadControl.setThreadAffinity(1 << sNextSearchThreadCPUIndex);
        LOGGER.info("  Bound search thread to vCPU:  " + sNextSearchThreadCPUIndex);

        // Calculate the next available virtual CPU for search threads.
        // If we're striping rollout threads with a stride greater than 1 then
        // then we can interleave the searchers.  If the rollout stride is 1 anyway
        // anything will clash so this is still as good a policy as any
        if (sNextSearchThreadCPUIndex == (CPUIdParity ? 1 : 0))
        {
          sNextSearchThreadCPUIndex = CPU_STRIPE_STRIDE + 1 + (CPUIdParity ? 1 : 0);
        }
        else
        {
          sNextSearchThreadCPUIndex += CPU_STRIPE_STRIDE;
        }
        sNextSearchThreadCPUIndex = sNextSearchThreadCPUIndex % NUM_CPUS;
      }
    }
  }

  /**
   * Register a rollout thread.
   */
  public static void registerRolloutThread()
  {
    if (USE_AFFINITY_MAPPING)
    {
      synchronized (ThreadControl.class)
      {
        // Bind this thread to the selected virtual CPU.
        ThreadControl.setThreadAffinity(1 << sNextRolloutThreadCPUIndex);
        LOGGER.info("  Bound rollout processor to vCPU: " + sNextRolloutThreadCPUIndex);

        // Calculate the next available virtual CPU for rollout threads, wrapping as required and leaving a gap for the
        // search thread if required.
        if (sNextRolloutThreadCPUIndex == (CPUIdParity ? 1 : 0))
        {
          sNextRolloutThreadCPUIndex = CPU_STRIPE_STRIDE + (CPUIdParity ? 1 : 0);
        }
        else
        {
          sNextRolloutThreadCPUIndex += CPU_STRIPE_STRIDE;
        }
        sNextRolloutThreadCPUIndex = sNextRolloutThreadCPUIndex % NUM_CPUS;
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