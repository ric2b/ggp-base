package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.player.gamer.statemachine.sancho.Sancho.TreeNodeRef;
import org.ggp.base.player.gamer.statemachine.sancho.Sancho.TreePath;
import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.TestForwardDeadReckonPropnetStateMachine;

class RolloutRequest
{
  /**
   *
   */
  private final RolloutProcessorPool           pool;
  public TreeNodeRef                           node;
  public ForwardDeadReckonInternalMachineState state;
  public double[]                              averageScores;
  public double[]                              averageSquaredScores;
  public int                                   sampleSize;
  public TreePath                              path;

  public RolloutRequest(RolloutProcessorPool pool)
  {
    this.pool = pool;
    averageScores = new double[pool.numRoles];
    averageSquaredScores = new double[pool.numRoles];
  }

  public void process(TestForwardDeadReckonPropnetStateMachine stateMachine)
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    ProfileSection methodSection = new ProfileSection("TreeNode.rollOut");
    try
    {
      synchronized (pool)
      {
        pool.dequeuedRollouts++;
      }
      double[] scores = new double[pool.numRoles];

      //playedMoveWeights = stateMachine.createMoveWeights();

      for (int roleIndex = 0; roleIndex < pool.numRoles; roleIndex++)
      {
        averageScores[roleIndex] = 0;
        averageSquaredScores[roleIndex] = 0;
      }

      for (int i = 0; i < sampleSize; i++)
      {
        //System.out.println("Perform rollout from state: " + state);
        pool.numNonTerminalRollouts++;
        stateMachine.getDepthChargeResult(state, pool.ourRole, null, null, null);

        for (int roleIndex = 0; roleIndex < pool.numRoles; roleIndex++)
        {
          int score = stateMachine.getGoal(pool.roleOrdering.roleIndexToRole(roleIndex));
          averageScores[roleIndex] += score;
          averageSquaredScores[roleIndex] += score * score;
          scores[pool.roleOrdering.roleIndexToRawRoleIndex(roleIndex)] = score;

          if (roleIndex == 0)
          {
            if (score > pool.highestRolloutScoreSeen)
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

      pool.completedRollouts.add(this);
      synchronized (pool)
      {
        pool.enqueuedCompletedRollouts++;
      }
    }
    finally
    {
      methodSection.exitScope();
    }
  }
}