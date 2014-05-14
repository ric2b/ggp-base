package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class RolloutProcessorPool
{
  private final Pipeline                               mPipeline;
  private final RolloutProcessor[]                     mRolloutProcessors;

  /**
   * Create a pool of rollout processors.
   *
   * @param xiPipeline - the work pipeline.
   * @param xiUnderlyingStateMachine - the underlying state machine.
   * @param xiCharacteristics - game characteristics.
   * @param xiRoleOrdering - the role ordering.
   */
  public RolloutProcessorPool(Pipeline xiPipeline,
                              ForwardDeadReckonPropnetStateMachine xiUnderlyingStateMachine,
                              RuntimeGameCharacteristics xiCharacteristics,
                              RoleOrdering xiRoleOrdering)
  {
    mPipeline = xiPipeline;
    mRolloutProcessors = new RolloutProcessor[ThreadControl.ROLLOUT_THREADS];
    for (int lii = 0; lii < ThreadControl.ROLLOUT_THREADS; lii++)
    {
      mRolloutProcessors[lii] = new RolloutProcessor(lii,
                                                     mPipeline,
                                                     xiUnderlyingStateMachine.createInstance(),
                                                     xiCharacteristics,
                                                     xiRoleOrdering);
    }
  }

  /**
   * Stop all rollout processors.
   */
  public void stop()
  {
    System.out.println("Stop rollout processors");

    for (int lii = 0; lii < ThreadControl.ROLLOUT_THREADS; lii++)
    {
      mRolloutProcessors[lii].stop();
    }

    System.out.println("Finished stopping rollout processors");
  }

  /**
   * Disable greedy rollouts for all rollout processors.
   */
  public void disableGreedyRollouts()
  {
    for (int ii = 0; ii < ThreadControl.ROLLOUT_THREADS; ii++)
    {
      mRolloutProcessors[ii].disableGreedyRollouts();
    }
  }
}
