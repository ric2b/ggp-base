// MobilityHeuristic.java
// (C) COPYRIGHT METASWITCH NETWORKS 2014
package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import java.util.Arrays;

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
  private MobilityData mTuningData;

  public int mWeight = 10; // !! ARR Hack

  @Override
  public void tuningInitialise(ForwardDeadReckonPropnetStateMachine xiStateMachine,
                               RoleOrdering xiRoleOrdering)
  {
    mEnabled = true;

    mStateMachine = xiStateMachine;
    mRoleOrdering = xiRoleOrdering;

    mTuningData = new MobilityData(xiStateMachine.getRoles().size());
    mCorrelationForRole = new PearsonCorrelation[mTuningData.mNumRoles];
    for (int lii = 0; lii < mTuningData.mNumRoles; lii++)
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
    for (int lii = 0; lii < mTuningData.mNumRoles; lii++)
    {
      mTuningData.mTotalChoicesForRole[lii] = 0;
      mTuningData.mMovesWithChoiceForRole[lii] = 0;
    }
  }

  @Override
  public void tuningInterimStateSample(ForwardDeadReckonInternalMachineState xiState, int xiChoosingRoleIndex)
  {
    assert(!mStateMachine.isTerminal(xiState));

    // During a rollout, accumulate the total mobility for each role.  In cases where a role has a single move, assume
    // that it's a forced no-op.  !! ARR Could do better here by actually looking for likely no-op statements.
    try
    {
      for (int lii = 0; lii < mTuningData.mNumRoles; lii++)
      {
        Role lRole = mRoleOrdering.roleIndexToRole(lii);
        int lMobility = mStateMachine.getLegalMoves(xiState, lRole).size();
        if (lMobility > 1)
        {
          mTuningData.mMovesWithChoiceForRole[lii]++;
          mTuningData.mTotalChoicesForRole[lii] += lMobility;
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
    for (int lii = 0; lii < mTuningData.mNumRoles; lii++)
    {
      if (mTuningData.mMovesWithChoiceForRole[lii] > 2)
      {
        mCorrelationForRole[lii].sample((mTuningData.mTotalChoicesForRole[lii] * 100) /
                                         mTuningData.mMovesWithChoiceForRole[lii],
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
    for (int lii = 0; lii < mTuningData.mNumRoles; lii++)
    {
      double lCorrelation = mCorrelationForRole[lii].getCorrelation();
      System.out.println("Mobility heuristic correlation for role " + lii + " = " + lCorrelation);
      if (lCorrelation < MIN_HEURISTIC_CORRELATION)
      {
        System.out.println("Disabling mobility heuristic");
        mEnabled = false;
      }
    }

    if (mEnabled)
    {
      System.out.println("Mobility heuristic enabled");
    }
  }

  @Override
  public void newTurn(ForwardDeadReckonInternalMachineState xiState, TreeNode xiNode)
  {
    // We don't do anything relative to the current root, so there's nothing to store here.
  }

  @Override
  public double[] getHeuristicValue(ForwardDeadReckonInternalMachineState xiState,
                                    ForwardDeadReckonInternalMachineState xiPreviousState)
  {
    // Get the total mobility data from the previous state.
    MobilityData lMobilityData = ((MobilityData)(xiPreviousState.getHeuristicData(this)));

    if (lMobilityData == null)
    {
      // If there's no data in xiPreviousState this must be the very first call to getHeuristicValue, in the initial
      // state of the game.
      System.out.println("Creating MobilityData in initial state");
      lMobilityData = new MobilityData(mTuningData.mNumRoles);
      try
      {
        for (int lii = 0; lii < lMobilityData.mNumRoles; lii++)
        {
          Role lRole = mRoleOrdering.roleIndexToRole(lii);
          int lMobility = mStateMachine.getLegalMoves(xiPreviousState, lRole).size();
          if (lMobility > 1)
          {
            lMobilityData.mMovesWithChoiceForRole[lii]++;
            lMobilityData.mTotalChoicesForRole[lii] += lMobility;
          }
        }
      }
      catch (MoveDefinitionException lEx)
      {
        System.err.println("Unexpected error getting legal moves");
        lEx.printStackTrace();
      }

      xiPreviousState.putHeuristicData(this, lMobilityData);
    }

    // Clone the mobility data for the new state, so as not to affect the version stored in the parent.
    lMobilityData = lMobilityData.clone();

    // Add the mobility for this state.
    int lGrandTotalChoices = 0;
    int lGrandTotalMovesWithChoices = 0;
    try
    {
      for (int lii = 0; lii < lMobilityData.mNumRoles; lii++)
      {
        Role lRole = mRoleOrdering.roleIndexToRole(lii);
        int lMobility = mStateMachine.getLegalMoves(xiState, lRole).size();
        if (lMobility > 1)
        {
          lMobilityData.mMovesWithChoiceForRole[lii]++;
          lMobilityData.mTotalChoicesForRole[lii] += lMobility;
        }
        lGrandTotalMovesWithChoices += lMobilityData.mMovesWithChoiceForRole[lii];
        lGrandTotalChoices += lMobilityData.mTotalChoicesForRole[lii];
      }
    }
    catch (MoveDefinitionException lEx)
    {
      System.err.println("Unexpected error getting legal moves");
      lEx.printStackTrace();
    }

    // Store the updated mobility data against the new state.
    xiState.putHeuristicData(this, lMobilityData);

    // Normalise the data to get heuristic values for the new state.
    double lAverageMobilityPerTurn = lGrandTotalChoices / lGrandTotalMovesWithChoices;
    double[] lHeuristicValue = new double[lMobilityData.mNumRoles];
    for (int lii = 0; lii < lMobilityData.mNumRoles; lii++)
    {
      if (lMobilityData.mMovesWithChoiceForRole[lii] == 0)
      {
        // This role hasn't had any moves where it can make a choice yet.  Assume it'll get an average result.
        lHeuristicValue[lii] = 50;
      }
      else
      {
        // Normalise using the logistic function (scaled to 0 - 100).
        double lRoleAverage = (double)lMobilityData.mTotalChoicesForRole[lii] /
                              (double)lMobilityData.mMovesWithChoiceForRole[lii];
        double lDeviation = (lRoleAverage - lAverageMobilityPerTurn) / lAverageMobilityPerTurn;
        lHeuristicValue[lii] = 100 / (1 + Math.exp(-lDeviation));
      }
    }

    return lHeuristicValue;
  }

  @Override
  public int getSampleWeight()
  {
    return mWeight;
  }

  @Override
  public boolean isEnabled()
  {
    return mEnabled;
  }

  @Override
  public Heuristic createIndependentInstance()
  {
    //  We have no game-state dependent member state so one instance
    //  can freely be used in multiple contexts
    return this;
  }

  private static class MobilityData
  {
    public final int mNumRoles;
    public final int[] mTotalChoicesForRole;
    public final int[] mMovesWithChoiceForRole;

    public MobilityData(int xiNumRoles)
    {
      mNumRoles = xiNumRoles;
      mTotalChoicesForRole = new int[xiNumRoles];
      mMovesWithChoiceForRole = new int[xiNumRoles];
    }

    /**
     * Cloning constructor.
     *
     * @param xiOther - the source object.
     */
    private MobilityData(MobilityData xiOther)
    {
      mNumRoles = xiOther.mNumRoles;
      mTotalChoicesForRole = Arrays.copyOf(xiOther.mTotalChoicesForRole, mNumRoles);
      mMovesWithChoiceForRole = Arrays.copyOf(xiOther.mMovesWithChoiceForRole, mNumRoles);
    }

    @Override
    public MobilityData clone()
    {
      return new MobilityData(this);
    }
  }
}
