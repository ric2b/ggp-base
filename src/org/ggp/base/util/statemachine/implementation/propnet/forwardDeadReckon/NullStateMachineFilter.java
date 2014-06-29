package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import java.util.Iterator;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveSet;
import org.ggp.base.util.statemachine.Role;

/**
 * @author steve
 * Null default filter that performs direct pass-through to an underlying state machine
 */
public class NullStateMachineFilter implements StateMachineFilter
{
  private final ForwardDeadReckonPropnetStateMachine stateMachine;

  /**
   * Construct a pass-through filter for the specified state machine
   * @param xiStateMachine
   */
  public NullStateMachineFilter(ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    stateMachine = xiStateMachine;
  }

  @Override
  public boolean isFilteredTerminal(ForwardDeadReckonInternalMachineState xiState)
  {
    return stateMachine.isTerminal(xiState);
  }

  @Override
  public int getFilteredMovesSize(ForwardDeadReckonInternalMachineState xiState,
                                  ForwardDeadReckonLegalMoveSet xiMoves,
                                  Role role,
                                  boolean xiIncludeForcedPseudoNoops)
  {
    return xiMoves.getNumChoices(role);
  }

  @Override
  public int getFilteredMovesSize(ForwardDeadReckonInternalMachineState xiState,
                                  ForwardDeadReckonLegalMoveSet xiMoves,
                                  int xiRoleIndex,
                                  boolean xiIncludeForcedPseudoNoops)
  {

    return xiMoves.getNumChoices(xiRoleIndex);
  }

  @Override
  public ForwardDeadReckonLegalMoveInfo nextFilteredMove(Iterator<ForwardDeadReckonLegalMoveInfo> xiItr)
  {
    return xiItr.next();
  }

}
