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

  private final Role                                 mOurRole;
  private final RoleOrdering                         mRoleOrdering;

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
                          RoleOrdering xiRoleOrdering)
  {
    mThreadIndex = xiThreadIndex;

    mPipeline = xiPipeline;

    mStateMachine = xiStateMachine;
    mStateMachine.setTerminalCheckHorizon(500);

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

    // Publish performance information every few seconds.  We do this moderately more frequently than the statistics are
    // used so that it doesn't matter whether the latest set of statistics fall just one side or just the other side of
    // a recalculation of the sample size.
    final long lPerfStatsUpdateInterval = (GameSearcher.SAMPLE_SIZE_UPDATE_INTERVAL_MS * 1000000L) / 5;

    long lNow = System.nanoTime();
    long lNextPerfStatsReportTime = lNow + lPerfStatsUpdateInterval;

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
      mPipeline.completedRollout(mThreadIndex);

      // Get timing information
      lNow = System.nanoTime();
      lUsefulWork += lNow;

      // Occasionally, update the sample size
      if ((GameSearcher.USE_DYNAMIC_SAMPLE_SIZING) && (lNow > lNextPerfStatsReportTime))
      {
        publishPerfStats(lUsefulWork, lBlockedFor);
        lNextPerfStatsReportTime += lPerfStatsUpdateInterval;
      }

      lBlockedFor -= lNow;
    }
  }

  /**
   * Publish performance statistics.
   *
   * @param xiUsefulWork - the number of nanoseconds of useful work carried out by this thread.
   * @param xiBlockedFor - the number of nanoseconds this thread has been blocked on the pipeline.
   */
  private void publishPerfStats(long xiUsefulWork, long xiBlockedFor)
  {
    RolloutPerfStats lStats = new RolloutPerfStats(xiUsefulWork, xiBlockedFor);
    mPipeline.publishRolloutPerfStats(mThreadIndex, lStats);
    System.out.println(".");
  }
}