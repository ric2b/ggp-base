package org.ggp.base.player.gamer.statemachine.sancho;

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
  public boolean                               mRecordPlayoutTrace;
  public boolean                               mIsWin;
  public Factor                                mFactor = null;
  public int                                   mSampleSize;
  public final double[]                        mAverageScores;
  public final double[]                        mAverageSquaredScores;
  public double                                mWeight;
  public boolean                               mComplete;  //  Result is known to complete even though the node is not terminal
  public MCTSTree                              mTree;
  public int                                   mMinScore;
  public int                                   mMaxScore;
  public int                                   mThreadId;
  private final int[]                          latchedScoreRangeBuffer = new int[2];
  public final ForwardDeadReckonLegalMoveInfo[] mPlayoutTrace;
  public int                                   mPlayoutLength;

  public long                                  mSelectElapsedTime;
  public long                                  mExpandElapsedTime;
  public long                                  mGetSlotElapsedTime;
  public long                                  mEnqueueTime;
  public long                                  mRolloutStartTime;
  public long                                  mEnqueue2Time;
  public long                                  mBackPropStartTime;
  public long                                  mCompletionTime;

  public long                                  mQueueLatency;

  private final ForwardDeadReckonPropnetStateMachine.PlayoutInfo mPlayoutInfo;

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
    mState = underlyingStateMachine.createEmptyInternalState();
    mPlayoutTrace = new ForwardDeadReckonLegalMoveInfo[MCTSTree.MAX_SUPPORTED_TREE_DEPTH];
    mPlayoutInfo = underlyingStateMachine.new PlayoutInfo();
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
    for (int roleIndex = 0; roleIndex < lNumRoles; roleIndex++)
    {
      mAverageScores[roleIndex] = 0;
      mAverageSquaredScores[roleIndex] = 0;
    }
    mMinScore = 1000;
    mMaxScore = -100;
    mWeight = 0;
    mComplete = false;
    mIsWin = false;

    mPlayoutInfo.factor = mFactor;
    mPlayoutInfo.cutoffDepth = mTree.mWeightDecayCutoffDepth;
    mPlayoutInfo.playoutTrace = (mRecordPlayoutTrace ? mPlayoutTrace : null);
    // Perform the requested number of samples.
    for (int i = 0; i < mSampleSize && !mComplete; i++)
    {
      stateMachine.getDepthChargeResult(mState, mPlayoutInfo);

      mPlayoutLength = mPlayoutInfo.playoutLength;

      double weight = (mTree.mWeightDecayKneeDepth == -1 ? 1 : 1 - sigma((mPlayoutInfo.playoutLength-mTree.mWeightDecayKneeDepth)/mTree.mWeightDecayScaleFactor));
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

          if (mRecordPlayoutTrace)
          {
            stateMachine.getLatchedScoreRange(mState, xiRoleOrdering.roleIndexToRole(0), latchedScoreRangeBuffer);

            if ( lScore == latchedScoreRangeBuffer[1] && latchedScoreRangeBuffer[1] > latchedScoreRangeBuffer[0] )
            {
              // Found a win.  Record the fact, and preserve the winning moves.
              mIsWin = true;
              mRecordPlayoutTrace = false;
            }
          }
        }
      }

      //  For fixed sum games, if greedy rollouts are being employed, the last 2 movbes on the played path
      //  are guaranteed to be optimal, so a depth lower than this implies a complete node immediately
      if ( mPlayoutLength < 2 && stateMachine.getIsGreedyRollouts() && mTree.gameCharacteristics.getIsFixedSum() )
      {
        mComplete = true;
      }
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
}