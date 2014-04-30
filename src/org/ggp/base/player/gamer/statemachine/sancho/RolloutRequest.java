package org.ggp.base.player.gamer.statemachine.sancho;

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

  public RolloutRequest(RolloutProcessorPool xiPool)
  {
    this.pool = xiPool;
    averageScores = new double[xiPool.numRoles];
    averageSquaredScores = new double[xiPool.numRoles];
  }

  /**
   * Process this rollout request.
   *
   * @param stateMachine - a state machine to handle perform the rollouts.
   *
   * @throws TransitionDefinitionException
   * @throws MoveDefinitionException
   * @throws GoalDefinitionException
   */
  public void process(ForwardDeadReckonPropnetStateMachine stateMachine)
      throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
  {
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
      pool.completedRollouts.add(this);
    }
    finally
    {
      methodSection.exitScope();
    }
  }
}