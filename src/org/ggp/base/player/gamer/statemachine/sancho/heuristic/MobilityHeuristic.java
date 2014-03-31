// MobilityHeuristic.java
// (C) COPYRIGHT METASWITCH NETWORKS 2014
package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import org.ggp.base.player.gamer.statemachine.sancho.RoleOrdering;
import org.ggp.base.player.gamer.statemachine.sancho.TreeNode;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;
import org.ggp.base.util.stats.PearsonCorrelation;

/**
 * Heuristic which assumes that it's better to have more choices of move (greater "mobility").
 */
public class MobilityHeuristic implements Heuristic
{
  private static final double MIN_HEURISTIC_CORRELATION = 0.1;

  private boolean mEnabled;

  private ForwardDeadReckonPropnetStateMachine mStateMachine;
  private RoleOrdering mRoleOrdering;

  private PearsonCorrelation[] mCorrelationForRole;
  private int mNumRoles;
  private int mTotalChoicesForRole[];
  private int mMovesWithChoiceForRole[];

  @Override
  public void tuningInitialise(ForwardDeadReckonPropnetStateMachine xiStateMachine,
                               RoleOrdering xiRoleOrdering)
  {
    mEnabled = true;

    mStateMachine = xiStateMachine;
    mRoleOrdering = xiRoleOrdering;

    mNumRoles = xiStateMachine.getRoles().size();
    mCorrelationForRole = new PearsonCorrelation[mNumRoles];
    mTotalChoicesForRole = new int[mNumRoles];
    mMovesWithChoiceForRole = new int[mNumRoles];
    for (int lii = 0; lii < mNumRoles; lii++)
    {
      mCorrelationForRole[lii] = new PearsonCorrelation();
    }

    tuningInitRollout();
  }

  /**
   * Set up state in preparation for a rollout.
   */
  private void tuningInitRollout()
  {
    for (int lii = 0; lii < mNumRoles; lii++)
    {
      mTotalChoicesForRole[lii] = 0;
      mMovesWithChoiceForRole[lii] = 0;
    }
  }

  @Override
  public void tuningInterimStateSample(ForwardDeadReckonInternalMachineState xiState, int xiChoosingRoleIndex)
  {
    assert(!mStateMachine.isTerminal(xiState));

    try
    {
      for (int lii = 0; lii < mNumRoles; lii++)
      {
        Role lRole = mRoleOrdering.roleIndexToRole(lii);
        int lMobility = mStateMachine.getLegalMoves(xiState, lRole).size();
        if (lMobility > 1)
        {
          mMovesWithChoiceForRole[lii]++;
          mTotalChoicesForRole[lii] += lMobility;
        }
      }
    }
    catch (MoveDefinitionException lEx)
    {
      System.err.println("Unexpected error getting legal moves");
      lEx.printStackTrace();
    }
  }

  @Override
  public void tuningTerminalStateSample(ForwardDeadReckonInternalMachineState xiState,
                                        int[] xiRoleScores)
  {
    assert(mStateMachine.isTerminal(xiState));

    // For each role, record the correlation between the average number of turns (x 100 to mitigate rounding) and the
    // final goal value.
    for (int lii = 0; lii < mNumRoles; lii++)
    {
      if (mMovesWithChoiceForRole[lii] > 2)
      {
        mCorrelationForRole[lii].sample((mTotalChoicesForRole[lii] * 100) / mMovesWithChoiceForRole[lii],
                                        xiRoleScores[lii]);
      }
    }

    // Re-initialize for the next rollout.
    tuningInitRollout();
  }

  @Override
  public void tuningComplete()
  {
    // See if overall game mobility is a good predictor of final score and enable/disable the heuristic on that basis.
    for (int lii = 0; lii < mNumRoles; lii++)
    {
      double lCorrelation = mCorrelationForRole[lii].getCorrelation();
      System.out.println("Mobility heuristic correlation for role " + lii + " = " + lCorrelation);
      if (lCorrelation < MIN_HEURISTIC_CORRELATION)
      {
        System.out.println("Disabling mobility heuristic");
        mEnabled = false;
      }
    }

    // It isn't safe to use the state machine any more (for multi-threading reasons)
    mStateMachine = null;
  }

  @Override
  public void newTurn(ForwardDeadReckonInternalMachineState xiState, TreeNode xiNode)
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
    return 10;
  }

  @Override
  public boolean isEnabled()
  {
    return mEnabled;
  }
}
