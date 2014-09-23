package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import java.util.Arrays;

import org.apache.commons.lang.mutable.MutableDouble;
import org.ggp.base.player.gamer.statemachine.sancho.RoleOrdering;
import org.ggp.base.player.gamer.statemachine.sancho.TreeNode;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

/**
 * Available Goal Heuristic.
 *
 * - The heuristic value is the average of all goals that aren't negatively latched.
 * - The heuristic weight is 0 if there is no change in value from the parent state and 10 otherwise.
 *
 * In a game like Escort-Latch Breakthrough, this should give a suitable kick for taking a king / keeping ours.
 */
public class AvailableGoalHeuristic implements Heuristic
{
  private boolean                              mEnabled;
  private ForwardDeadReckonPropnetStateMachine mStateMachine;
  private RoleOrdering                         mRoleOrdering;

  @Override
  public boolean tuningInitialise(ForwardDeadReckonPropnetStateMachine xiStateMachine, RoleOrdering xiRoleOrdering)
  {
    // We're enabled iff there are any negative goal latches.
    mEnabled = xiStateMachine.hasNegativelyLatchedGoals();
    mStateMachine = xiStateMachine;
    mRoleOrdering = xiRoleOrdering;
    return mEnabled;
  }

  @Override
  public void tuningInterimStateSample(ForwardDeadReckonInternalMachineState xiState, int xiChoosingRoleIndex)
  {
    // !! ARR Auto-generated method stub

  }

  @Override
  public void tuningTerminalStateSample(ForwardDeadReckonInternalMachineState xiState, int[] xiRoleScores)
  {
    // !! ARR Auto-generated method stub

  }

  @Override
  public void tuningComplete()
  {
    // !! ARR Auto-generated method stub

  }

  @Override
  public void newTurn(ForwardDeadReckonInternalMachineState xiState, TreeNode xiNode)
  {
    // !! ARR Auto-generated method stub

  }

  @Override
  public double getHeuristicValue(ForwardDeadReckonInternalMachineState xiState,
                                  int choosingRoleIndex,
                                  ForwardDeadReckonInternalMachineState xiPreviousState,
                                  ForwardDeadReckonInternalMachineState xiHeuristicStabilityState,
                                  double[] xoHeuristicValue,
                                  MutableDouble xoHeuristicWeight)
  {
    // Compute the heuristic value
    mStateMachine.getAverageAvailableGoals(xiState, mRoleOrdering, xoHeuristicValue);
    xiState.putHeuristicData(this, Arrays.copyOf(xoHeuristicValue, xoHeuristicValue.length));

    // If the heuristic value differs from our parent, set weight 10, otherwise 0.  (In children of the root state, it
    // isn't sufficiently interesting/reliable use.)
    xoHeuristicWeight.setValue(0);
    if (xiPreviousState != null)
    {
      double[] lParentValues = (double[])xiPreviousState.getHeuristicData(this);
      if (lParentValues == null)
      {
        // !! ARR Not clear under what conditions we might not have calculated the parent values, but it certainly
        // !!     happens sometimes.
        lParentValues = new double[xoHeuristicValue.length];
        mStateMachine.getAverageAvailableGoals(xiPreviousState, mRoleOrdering, lParentValues);
        xiPreviousState.putHeuristicData(this, lParentValues);
      }

      for (int lii = 0; lii < xoHeuristicValue.length; lii++)
      {
        if (lParentValues[lii] != xoHeuristicValue[lii])
        {
          xoHeuristicWeight.setValue(10);
          break;
        }
      }
    }

    return 0;
  }

  @Override
  public boolean isEnabled()
  {
    return mEnabled;
  }

  @Override
  public Heuristic createIndependentInstance()
  {
    // There's no game-state-dependent persistent state, so we can re-use this instance.
    return this;
  }

  @Override
  public void tuningStartSampleGame()
  {
    // TODO Auto-generated method stub

  }
}
