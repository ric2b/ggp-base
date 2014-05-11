package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

/**
 * A rollout processor, running on its own thread.
 */
class RolloutProcessor implements Runnable
{
  private final int                                  mThreadIndex;
  private final Pipeline                             mPipeline;
  private final ForwardDeadReckonPropnetStateMachine mStateMachine;
  private final Thread                               mThread;
  private final RuntimeGameCharacteristics           mCharacteristics;
  private final boolean                              USE_DYNAMIC_SAMPLE_SIZING = true;

  public final Role                                  mOurRole;
  public final RoleOrdering                          mRoleOrdering;

  /**
   * Create (and start) a rollout processor.
   *
   * @param xiThreadIndex - the thread index.
   * @param xiPipeline - the pipeline from which to get work.
   * @param xiStateMachine - a state machine for performing the work.
   * @param xiCharacteristics - game characteristics.
   * @param xiRoleOrdering - role ordering.
   */
  public RolloutProcessor(int xiThreadIndex,
                          Pipeline xiPipeline,
                          ForwardDeadReckonPropnetStateMachine xiStateMachine,
                          RuntimeGameCharacteristics xiCharacteristics,
                          RoleOrdering xiRoleOrdering)
  {
    mThreadIndex = xiThreadIndex;

    mPipeline = xiPipeline;

    mStateMachine = xiStateMachine;
    mStateMachine.setTerminalCheckHorizon(500);

    mCharacteristics = xiCharacteristics;

    mRoleOrdering = xiRoleOrdering;
    mOurRole = mRoleOrdering.roleIndexToRole(0);

    mThread = new Thread(this, "Rollout Processor " + mThreadIndex);
    mThread.setDaemon(true);
    mThread.start();
  }

  /**
   * Disable greedy rollouts.
   *
   * This method may only be called when it is known that this rollout processor isn't processing anything.
   */
  public void disableGreedyRollouts()
  {
    mStateMachine.disableGreedyRollouts();
  }

  /**
   * Stop this rollout processor.
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
      System.err.println("Unexpectedly interrupted whilst stopping rollout processor " + mThreadIndex);
    }

    if (mThread.getState() != Thread.State.TERMINATED)
    {
      System.err.println("Failed to stop rollout processor " + mThreadIndex);
    }
  }

  @Override
  public void run()
  {
    // Register this thread.
    ThreadControl.registerRolloutThread();

    // Update the sample size every 10 seconds.
    final long lUpdateSampleSizeInterval = 10000000000L;

    long lNow = System.nanoTime();
    long lNextReportTime = lNow + lUpdateSampleSizeInterval;

    long lUsefulWork = 0;
    long lBlockedFor = -lNow;

    // Continually process requests until interrupted.
    for (RolloutRequest lRequest = mPipeline.getNextRolloutRequest(mThreadIndex);
         !Thread.interrupted();
         lRequest = mPipeline.getNextRolloutRequest(mThreadIndex))
    {
      // Get timing information
      lNow = System.nanoTime();
      lUsefulWork -= lNow;
      lBlockedFor += lNow;

      // Do the rollouts
      lRequest.process(mStateMachine, mOurRole, mRoleOrdering);
      mPipeline.rolloutComplete(mThreadIndex);

      // Get timing information
      lNow = System.nanoTime();
      lUsefulWork += lNow;

      // Occasionally, update the sample size
      if (USE_DYNAMIC_SAMPLE_SIZING)
      {
        if (lNow > lNextReportTime)
        {
          updateSampleSize(lUsefulWork, lBlockedFor);

          lNextReportTime += lUpdateSampleSizeInterval;
          lUsefulWork = 0;
          lBlockedFor = 0;
        }
      }

      lBlockedFor -= lNow;
    }
  }

  private void updateSampleSize(long xiUsefulWork, long xiBlockedFor)
  {
    // Print debugging information from all threads.
    long lSampleSize = mCharacteristics.getRolloutSampleSize();
    double lUsefulWorkRatio = ((double)xiUsefulWork / (double)(xiUsefulWork + xiBlockedFor));
    System.out.println("Thread " + mThreadIndex + " did " + lUsefulWorkRatio + " useful work");

    // Thread 0 is responsible for setting the sample size (to avoid locking)
    if (mThreadIndex == 0)
    {
      // Aim to keep the rollout threads 80% busy.  We don't want to have them fully occupied because then the tree
      // worker thread is likely to block waiting for a rollout slot.
      double lNewSampleSize = lSampleSize * 0.8 / lUsefulWorkRatio;

      // The measured ratio is really quite volatile.  To prevent the sample size jumping all over the place, adjust it
      // slowly.  The rules vary depending on whether the current sample size is very small (in which case we need to
      // take care to avoid rounding preventing any change).
      if (lSampleSize > 4)
      {
        // Only let the sample size 33% of the way towards its new value.  Also, only let it grow to 150% of its
        // previous value in one go.
        lNewSampleSize = (lNewSampleSize + (2 * lSampleSize)) / 3;
        lNewSampleSize = Math.min(1.5 * lSampleSize, lNewSampleSize);
      }
      else
      {
        // Very small sample size.  Jump straight to the new size, up to a maximum of 5.  Instead of always rounding
        // down, do normal rounding.
        lNewSampleSize = Math.min(5,  lNewSampleSize + 0.5);
      }

      // The sample size is always absolutely bound between 1 and 100 (inclusive).
      int lBoundedSampleSize = Math.max(1,  Math.min(100, (int)lNewSampleSize));

      System.out.println("Setting sample size = " + lBoundedSampleSize);

      mCharacteristics.setRolloutSampleSize(lBoundedSampleSize);
    }
  }
}