package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.Queue;

import org.ggp.base.player.gamer.statemachine.sancho.TreeNode.TreeNodeRef;
import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.Factor;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

class RolloutRequest
{
  private final RolloutProcessorPool           pool;
  public TreeNodeRef                           node;
  public ForwardDeadReckonInternalMachineState state;
  public Factor                                factor = null;
  public final double[]                        averageScores;
  public final double[]                        averageSquaredScores;
  public int                                   sampleSize;
  public TreePath                              path;
  private final Queue<RolloutRequest>          mCompletionQueue;

  /**
   * Timings for this request.  All times and durations are measured in nanoseconds.
   *
   * These timings allow us to determine the relative time spent in the tree processing thread (expansion & stats
   * updates) vs the rollout processor.  This in turn allows us to calculate the appropriate sample size to keep all
   * threads busy.
   */
  private long  mTreeThreadDuration = 0;
  private long  mRolloutThreadDuration = 0;

  public RolloutRequest(RolloutProcessorPool xiPool, Queue xiCompletionQueue)
  {
    startTreeWork();
    this.pool = xiPool;
    averageScores = new double[xiPool.numRoles];
    averageSquaredScores = new double[xiPool.numRoles];
    mCompletionQueue = xiCompletionQueue;
  }

  /**
   * Process this rollout request.
   *
   * @param stateMachine - a state machine to handle perform the rollouts.
   */
  public void process(ForwardDeadReckonPropnetStateMachine stateMachine)
  {
    long lRolloutStartTime = System.nanoTime();

    ProfileSection methodSection = ProfileSection.newInstance("TreeNode.rollOut");
    try
    {
      double[] scores = new double[pool.numRoles];

      //playedMoveWeights = stateMachine.createMoveWeights();

      for (int roleIndex = 0; roleIndex < pool.numRoles; roleIndex++)
      {
        averageScores[roleIndex] = 0;
        averageSquaredScores[roleIndex] = 0;
      }

      for (int i = 0; i < sampleSize; i++)
      {
        //long startTime = System.nanoTime();
        //System.out.println("Perform rollout from state: " + state);
         stateMachine.getDepthChargeResult(state, factor, pool.ourRole, null, null, null);

        //long rolloutTime = System.nanoTime() - startTime;
        //System.out.println("Rollout took: " + rolloutTime);
        for (int roleIndex = 0; roleIndex < pool.numRoles; roleIndex++)
        {
          int score = stateMachine.getGoal(pool.roleOrdering.roleIndexToRole(roleIndex));
          averageScores[roleIndex] += score;
          averageSquaredScores[roleIndex] += score * score;
          scores[pool.roleOrdering.roleIndexToRawRoleIndex(roleIndex)] = score;

          if (roleIndex == 0)
          {
            if (score > pool.highestRolloutScoreSeen) // !! ARR Not thread-safe
            {
              pool.highestRolloutScoreSeen = score;
            }
            if (score < pool.lowestRolloutScoreSeen)
            {
              pool.lowestRolloutScoreSeen = score;
            }
          }
        }
      }

      for (int roleIndex = 0; roleIndex < pool.numRoles; roleIndex++)
      {
        averageScores[roleIndex] /= sampleSize;
        averageSquaredScores[roleIndex] /= sampleSize;
      }

      // Add the completed rollout to the queue for updating the node statistics.  These are dequeued in
      // GameSearcher#processCompletedRollouts().
      mCompletionQueue.add(this);
    }
    catch (TransitionDefinitionException | MoveDefinitionException | GoalDefinitionException lEx)
    {
      lEx.printStackTrace();
    }
    finally
    {
      methodSection.exitScope();
      mRolloutThreadDuration = System.nanoTime() - lRolloutStartTime;
    }
  }

  /**
   * Notify this rollout request that we're starting to work on it on the tree thread (either doing select / expand) or
   * doing back-propagation.
   */
  public void startTreeWork()
  {
    mTreeThreadDuration -= System.nanoTime();
  }

  /**
   * Notify this rollout request that we've stopped working on it on the tree thread.
   */
  public void completeTreeWork()
  {
    mTreeThreadDuration += System.nanoTime();
  }

  /**
   * @return the time (in nanoseconds) spent in total in the tree thread.
   */
  public long getTreeThreadDuration()
  {
    return mTreeThreadDuration;
  }

  /**
   * @return the time (in nanoseconds) for all rollouts, but adjusted to assume just 1 sample per rollout.
   */
  public long getPerSampleRolloutDuration()
  {
    return mRolloutThreadDuration / sampleSize;
  }
}