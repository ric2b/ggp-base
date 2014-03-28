// MobilityHeuristic.java
// (C) COPYRIGHT METASWITCH NETWORKS 2014
package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import org.ggp.base.player.gamer.statemachine.sancho.TreeNode;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.implementation.propnet.TestForwardDeadReckonPropnetStateMachine;
import org.ggp.base.util.stats.PearsonCorrelation;

public class MobilityHeuristic implements Heuristic
{
  private boolean mEnabled;
  private PearsonCorrelation[] mCorrelationForRole;
  private int mNumRoles;
  private int mNumTurns;
  private int mTotalChoicesForRole[];
  private TestForwardDeadReckonPropnetStateMachine mStateMachine;

  @Override
  public void tuningInitialise(TestForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    mStateMachine = xiStateMachine;
    mEnabled = true;
    mNumRoles = xiStateMachine.getRoles().size();
    mCorrelationForRole = new PearsonCorrelation[mNumRoles];
    mTotalChoicesForRole = new int[mNumRoles];
    for (int lii = 0; lii < mNumRoles; lii++)
    {
      mCorrelationForRole[lii] = new PearsonCorrelation();
      mTotalChoicesForRole[lii] = 0;
    }

    tuningInitRollout();
  }

  /**
   * Set up state in preparation for a rollout.
   */
  private void tuningInitRollout()
  {
    mNumTurns = 0;
    for (int lii = 0; lii < mNumRoles; lii++)
    {
      mTotalChoicesForRole[lii] = 0;
    }
  }

  @Override
  public void tuningInterimStateSample(ForwardDeadReckonInternalMachineState xiState, int xiRoleIndex)
  {
    // Find the number of legal moves for the choosing role.
    // !! ARR How do you get the role from the role index?
    // mTotalChoicesForRole[xiRoleIndex] = mStateMachine.getLegalMoves(xiState, role[xiRoleIndex]);
    mNumTurns++;
  }

  @Override
  public void tuningTerminalStateSample(ForwardDeadReckonInternalMachineState xiState,
                                        int[] xiRoleScores)
  {
    if (mNumTurns > 2)
    {
      // Record the correlation between the average number of turns (x 100 to mitigate rounding) for each role.
      for (int lii = 0; lii < mNumRoles; lii++)
      {
        mCorrelationForRole[lii].sample((mTotalChoicesForRole[lii] * 100) / mNumTurns, xiRoleScores[lii]);
        mTotalChoicesForRole[lii] = 0;
      }
    }

    // Re-initialize for the next rollout.
    tuningInitRollout();
  }

  @Override
  public void tuningComplete()
  {
    // !! ARR Auto-generated method stub

    // See if overall game mobility is a good predictor of final score and enable/disable the heuristic on that basis.
  }

  @Override
  public void newTurn(ForwardDeadReckonInternalMachineState xiState,
                      TreeNode xiNode)
  {
    // !! ARR Auto-generated method stub
  }

  @Override
  public double[] getHeuristicValue(ForwardDeadReckonInternalMachineState xiState,
                                    ForwardDeadReckonInternalMachineState xiPreviousState)
  {
    // !! ARR Auto-generated method stub
    return null;
  }

  @Override
  public int getSampleWeight()
  {
    // !! ARR Auto-generated method stub
    return 0;
  }

  @Override
  public boolean isEnabled()
  {
    // !! ARR Auto-generated method stub
    return mEnabled;
  }
}
