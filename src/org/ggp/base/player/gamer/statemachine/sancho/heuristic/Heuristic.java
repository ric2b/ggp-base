package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.implementation.propnet.TestForwardDeadReckonPropnetStateMachine;

/**
 * Interface for heuristics.
 *
 * !! ARR Not quite right yet.
 *
 * <p>Sancho uses heuristics in meta-gaming and during move selection.
 *
 * <p><b>Meta-gaming</b>
 *
 * <p>During meta-gaming, Sancho initializes all heuristics and attempts to determine which heuristics are useful for
 * the game and what weight to assign to each.
 *
 * <p>To be useful, a heuristic must have a positive correlation with game score.  Each heuristic should attempt to
 * predict the final goal values for each player from a given position.  Each goal value should be in the range 0 - 100.
 * Heuristics which are not sufficiently useful are discarded during meta-gaming.
 *
 * <p>(The weight applied to each heuristic is a tree-specific value.  The heuristic need not concern itself with
 * assigning a weight.)  !! ARR Totally bogus.
 *
 * <p><b>Move selection</b>
 *
 * <p>During move selection, Sancho asks for the heuristic value of each node, on expansion.  Bearing in mind that
 * heuristics are intended to assist with move selection, Sancho uses the difference in heuristic value between the
 * current and new states.  This amplifies the effect of the heuristic (by removing any constant portion due to the
 * current state).
 */
public interface Heuristic
{
  //	Initialize with a given a state machine to analyse
  public void init(TestForwardDeadReckonPropnetStateMachine stateMachine);

  //	Accrue a state sample from each state within a rollout
  public void accrueInterimStateSample(ForwardDeadReckonInternalMachineState state,
                                                int choosingRoleIndex);

  //	Accrue a rollout sample (terminal state from a rollout)
  public void accrueTerminalStateSample(ForwardDeadReckonInternalMachineState state,
                                                 double[] roleScores);

  //	Complete the analysis
  public void completeAnalysis();

  double[] getHeuristicValue(ForwardDeadReckonInternalMachineState state,
                             ForwardDeadReckonInternalMachineState previousState);

  int getSampleWeight();
}
