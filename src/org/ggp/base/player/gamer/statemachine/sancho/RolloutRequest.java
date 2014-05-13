package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.player.gamer.statemachine.sancho.TreeNode.TreeNodeRef;
import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.Factor;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

/**
 * Request object holding all the information about a rollout.
 *
 * These objects are pre-allocated by the pipeline and taken/released by various threads, each performing their own part
 * of the rollout.
 */
class RolloutRequest
{
  public TreeNodeRef                           mNode;
  public TreePath                              mPath;
  public ForwardDeadReckonInternalMachineState mState;
  public Factor                                mFactor = null;
  public int                                   mSampleSize;
  public final double[]                        mAverageScores;
  public final double[]                        mAverageSquaredScores;
  public int                                   mMinScore;
  public int                                   mMaxScore;
  public long                                  mEnqueueTime;
  public long                                  mQueueLatency;
  public int                                   mThreadId;

  /**
   * Create a rollout request.
   *
   * @param xiNumRoles - the number of roles in the game (used to size arrays).
   */
  public RolloutRequest(int xiNumRoles)
  {
    mAverageScores = new double[xiNumRoles];
    mAverageSquaredScores = new double[xiNumRoles];
  }

  /**
   * Process this rollout request.
   *
   * @param stateMachine - a state machine to handle perform the rollouts.
   * @param xiOurRole - our role.
   * @param xiRoleOrdering - the role ordering.
   */
  public void process(ForwardDeadReckonPropnetStateMachine stateMachine,
                      Role xiOurRole,
                      RoleOrdering xiRoleOrdering)
  {
    int lNumRoles = stateMachine.getRoles().size();

    mQueueLatency = System.nanoTime() - mEnqueueTime;
    ProfileSection methodSection = ProfileSection.newInstance("TreeNode.rollOut");
    try
    {
      //playedMoveWeights = stateMachine.createMoveWeights();

      // Reset the scores.
      for (int roleIndex = 0; roleIndex < lNumRoles; roleIndex++)
      {
        mAverageScores[roleIndex] = 0;
        mAverageSquaredScores[roleIndex] = 0;
      }
      mMinScore = 1000;
      mMaxScore = -100;

      // Perform the request number of samples.
      for (int i = 0; i < mSampleSize; i++)
      {
        // Do the rollout.
        stateMachine.getDepthChargeResult(mState, mFactor, xiOurRole, null, null, null);

        // Record the results.
        for (int roleIndex = 0; roleIndex < lNumRoles; roleIndex++)
        {
          int lScore = stateMachine.getGoal(xiRoleOrdering.roleIndexToRole(roleIndex));
          mAverageScores[roleIndex] += lScore;
          mAverageSquaredScores[roleIndex] += lScore * lScore;

          // Check for new min/max.
          if (roleIndex == 0)
          {
            if (lScore > mMaxScore)
            {
              mMaxScore = lScore;
            }
            if (lScore < mMinScore)
            {
              mMinScore = lScore;
            }
          }
        }
      }

      // Normalize the results for the number of samples.
      for (int roleIndex = 0; roleIndex < lNumRoles; roleIndex++)
      {
        mAverageScores[roleIndex] /= mSampleSize;
        mAverageSquaredScores[roleIndex] /= mSampleSize;
      }
    }
    catch (TransitionDefinitionException | MoveDefinitionException | GoalDefinitionException lEx)
    {
      lEx.printStackTrace();
    }
    finally
    {
      methodSection.exitScope();
    }
  }
}