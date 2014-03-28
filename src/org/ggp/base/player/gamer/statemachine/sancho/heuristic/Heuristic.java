package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import org.ggp.base.player.gamer.statemachine.sancho.RoleOrdering;
import org.ggp.base.player.gamer.statemachine.sancho.TreeNode;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.implementation.propnet.TestForwardDeadReckonPropnetStateMachine;

/**
 * Interface for heuristics.
 *
 * <p>Each heuristic is used in 3 phases.
 *
 * <ol><li>Tuning
 *     <li>Validation and weighting
 *     <li>Move selection</ol>
 *
 * <p><b>Tuning</b>
 *
 * <p>During meta-gaming, Sancho initialises all heuristics and performs the tuning phase.  During this phase,
 * heuristics get the opportunity to collect data on a number of sample rollouts.  At the end of the phase, they may
 * set any internal tunable parameters.  There is no other output from this phase.
 *
 * <p><b>Validation and weighting</b>
 *
 * <p>During meta-gaming, Sancho attempts to determine whether each heuristic actually improves game-play and, if so,
 * the relative weight to apply to the heuristic.  The heuristic code should no take no special action during this phase
 * - it should simply act as in the move selection phase.
 *
 * <p>(Not yet implemented.)
 *
 * <p><b>Move selection</b>
 *
 * <p>During move selection, Sancho asks for the heuristic value of each node, on expansion.  Bearing in mind that
 * heuristics are intended to assist with move selection, Sancho uses the difference in heuristic value between the
 * current and new states.  This amplifies the effect of the heuristic (by removing any constant portion due to the
 * current state).
 *
 * <p>Heuristics should return an int[] for each queried state, where each value is in the range 0 - 100.  This should
 * be the best prediction of the final result of the game, according to the heuristic.
 *
 * <p>Heuristics should additionally return an indication of their confidence in the heuristic values.  This should be
 * in the range 0 - 10 where 0 indicates that the heuristic value is no better than a random number (and should
 * therefore be ignored) and 10 indicates the maximum certainty that this heuristic can ever have.  Heuristics with no
 * variable measure of their own confidence should always output 10.
 *
 * <p>Heuristics will not be asked for a value in a terminal state.
 */
public interface Heuristic
{
  /**
   * Initialise the heuristic and prepare for tuning.
   *
   * @param stateMachine - the state machine representation of the game.
   * @param roleOrdering - the canonical role ordering.
   */
  public void tuningInitialise(TestForwardDeadReckonPropnetStateMachine stateMachine,
                               RoleOrdering roleOrdering);

  /**
   * Update tuning state as a result of a single step of a rollout (i.e. a single move in a game).
   *
   * @param state             - the new state.
   * @param choosingRoleIndex - the role to choose a move in this state.
   */
  public void tuningInterimStateSample(ForwardDeadReckonInternalMachineState state, int choosingRoleIndex);

  /**
   * Update tuning state at the end of a single rollout (i.e. a sample match).
   *
   * @param state      - the terminal state.
   * @param roleScores - the goal values in the terminal state.
   */
  public void tuningTerminalStateSample(ForwardDeadReckonInternalMachineState state, int[] roleScores);

  /**
   * Complete the tuning phase, updating internal state as necessary.
   */
  public void tuningComplete();

  /**
   * Record the state of a turn.
   *
   * This method will always be called before {@link #getHeuristicValue} or {@link #getSampleWeight} and will be called
   * again at the start of each turn.
   *
   * @param xiState - the new state.
   * @param xiNode  - tree node representing the state.  !! ARR Hack, used by PieceHeuristic.
   */
  public void newTurn(ForwardDeadReckonInternalMachineState xiState, TreeNode xiNode);

  /**
   * Get the heuristic value for the specified state.
   *
   * @param state         - the state (never a terminal state).
   * @param previousState - the previous state (can be null).
   *
   * @return the heuristic value.
   */
  double[] getHeuristicValue(ForwardDeadReckonInternalMachineState state,
                             ForwardDeadReckonInternalMachineState previousState);

  /**
   * @return a weighting (in the range 0 - 10) reflecting confidence in the heuristic values being produced.
   */
  int getSampleWeight();

  /**
   * @return whether the heuristic should be used.
   */
  public boolean isEnabled();
}
