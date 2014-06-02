package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import org.ggp.base.player.gamer.statemachine.sancho.RoleOrdering;
import org.ggp.base.player.gamer.statemachine.sancho.TreeNode;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;
import org.w3c.tidy.MutableInteger;

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
  private boolean mEnabled;

  @Override
  public boolean tuningInitialise(ForwardDeadReckonPropnetStateMachine xiStateMachine, RoleOrdering xiRoleOrdering)
  {
    // We're enabled iff there are any negative goal latches.
    mEnabled = xiStateMachine.hasNegativelyLatchedGoals();
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
  public void getHeuristicValue(ForwardDeadReckonInternalMachineState xiState,
                                ForwardDeadReckonInternalMachineState xiPreviousState,
                                double[] xoHeuristicValue,
                                MutableInteger xoHeuristicWeight)
  {
    // !! ARR Auto-generated method stub
  }

  @Override
  public boolean isEnabled()
  {
    return mEnabled;
  }

  @Override
  public Heuristic createIndependentInstance()
  {
    // !! ARR Auto-generated method stub
    return null;
  }
}
