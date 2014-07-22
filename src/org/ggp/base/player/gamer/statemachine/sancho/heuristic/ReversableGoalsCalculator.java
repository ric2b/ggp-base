package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.GoalsCalculator;

public interface ReversableGoalsCalculator extends GoalsCalculator
{
  /**
   * Swap the masks between the (must be 2) roles
   */
  void reverseRoles();
}
