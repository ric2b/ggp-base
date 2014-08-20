package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.List;

import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.statemachine.Role;
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
  public long                                  mNodeRef;
  public TreePath                              mPath;
  public final ForwardDeadReckonInternalMachineState mState;
  public List<ForwardDeadReckonLegalMoveInfo>  mPlayedMovesForWin;
  public Factor                                mFactor = null;
  public int                                   mSampleSize;
  public final double[]                        mAverageScores;
  public final double[]                        mAverageSquaredScores;
  public double                                mWeight;
  public MCTSTree                              mTree;
  public int                                   mMinScore;
  public int                                   mMaxScore;
  public int                                   mThreadId;
  private final int[]                          latchedScoreRangeBuffer = new int[2];

  public long                                  mSelectElapsedTime;
  public long                                  mExpandElapsedTime;
  public long                                  mGetSlotElapsedTime;
  public long                                  mEnqueueTime;
  public long                                  mRolloutStartTime;
  public long                                  mEnqueue2Time;
  public long                                  mBackPropStartTime;
  public long                                  mCompletionTime;

  public long                                  mQueueLatency;

  /**
   * Create a rollout request.
   *
   * @param xiNumRoles - the number of roles in the game (used to size arrays).
   * @param underlyingStateMachine  - state machine of the game
   */
  public RolloutRequest(int xiNumRoles, ForwardDeadReckonPropnetStateMachine underlyingStateMachine)
  {
    mAverageScores = new double[xiNumRoles];
    mAverageSquaredScores = new double[xiNumRoles];
    mState = new ForwardDeadReckonInternalMachineState(underlyingStateMachine.getInfoSet());
  }

  private static double sigma(double x)
  {
    return 1/(1+Math.exp(-x));
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
    int lNumRoles = stateMachine.getRoles().length;

    mRolloutStartTime = System.nanoTime();
    mQueueLatency = mRolloutStartTime - mEnqueueTime;
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
      mWeight = 0;

      int numScoringRollouts = 0;

      List<ForwardDeadReckonLegalMoveInfo> playedMoves = mPlayedMovesForWin;

      // Perform the request number of samples.
      for (int i = 0; i < mSampleSize; i++)
      {
        if ( playedMoves != null )
        {
          playedMoves.clear();
        }

        // Do the rollout.
        int playoutLength = stateMachine.getDepthChargeResult(mState, mFactor, xiOurRole, null, null, playedMoves, mTree.mWeightDecayCutoffDepth);

        double weight = (mTree.mWeightDecayKneeDepth == -1 ? 1 : 1 - sigma((playoutLength-mTree.mWeightDecayKneeDepth)/mTree.mWeightDecayScaleFactor));
        assert(!Double.isNaN(weight));
        assert(weight > TreeNode.EPSILON);

        mWeight += weight;

        // Record the results.
        for (int roleIndex = 0; roleIndex < lNumRoles; roleIndex++)
        {
          int lScore = stateMachine.getGoal(xiRoleOrdering.roleIndexToRole(roleIndex));
          mAverageScores[roleIndex] += lScore*weight;
          mAverageSquaredScores[roleIndex] += lScore * lScore * weight;

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

            if ( playedMoves != null )
            {
              stateMachine.getLatchedScoreRange(mState, xiRoleOrdering.roleIndexToRole(0), latchedScoreRangeBuffer);

              if ( lScore == latchedScoreRangeBuffer[1] && latchedScoreRangeBuffer[1] > latchedScoreRangeBuffer[0] )

              //  Stop updating the played moves list since we have now found a win
              playedMoves = null;
            }
          }
        }
      }

      if ( playedMoves != null )
      {
        //  No win was found so don't report a win sequence
        mPlayedMovesForWin = null;
      }

      assert(!Double.isNaN(mAverageScores[0]));
      // Normalize the results for the number of samples.
      for (int roleIndex = 0; roleIndex < lNumRoles; roleIndex++)
      {
        mAverageScores[roleIndex] /= mWeight;
        mAverageSquaredScores[roleIndex] /= mWeight;
      }
      assert(!Double.isNaN(mAverageScores[0]));
    }
    finally
    {
      methodSection.exitScope();
    }
  }
}