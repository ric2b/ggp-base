package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class RolloutProcessorPool
{
  private final Pipeline                               mPipeline;
  private final RolloutProcessor[]                     mRolloutProcessors;
  private ForwardDeadReckonPropnetStateMachine         mUnderlyingStateMachine;

  // !! ARR These next few variables really don't belong here.
  public final int                                     mNumRoles;
  public final Role                                    mOurRole;
  public final RoleOrdering                            mRoleOrdering;

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

    mNumRoles = xiUnderlyingStateMachine.getRoles().size();
    mRoleOrdering = xiRoleOrdering;
    mOurRole = mRoleOrdering.roleIndexToRole(0);
    mUnderlyingStateMachine = xiUnderlyingStateMachine;
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

  /**
   * Enqueue a rollout request for processing.
   *
   * If the work backlog has grown to the limit, this method will block until a queue slot is available (or until
   * interrupted).
   *
   * @param xiRequest - the rollout request.
   *
   * @throws InterruptedException if the thread was interrupted whilst waiting to queue the request.
   */
  public void enqueueRequest(RolloutRequest xiRequest) throws InterruptedException
  {
    if (ThreadControl.ROLLOUT_THREADS > 0)
    {
      // Tell the rest of the pipeline that this rollout request is ready to work on.
      mPipeline.expandComplete();
    }
    else
    {
      // We're doing synchronous rollouts so just process the request now.
      assert(ThreadControl.ROLLOUT_THREADS == 0);
      xiRequest.process(mUnderlyingStateMachine, mOurRole, mRoleOrdering);
    }
  }
}
