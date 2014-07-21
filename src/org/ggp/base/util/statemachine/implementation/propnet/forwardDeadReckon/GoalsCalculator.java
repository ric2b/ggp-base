package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.Role;

/**
 * @author steve
 *  Interface through which goal calculations may be injected, bypassing the use
 *  of teh state machine
 */
public interface GoalsCalculator
{
  /**
   * @return a functional clone which can be used in a thread safe manner
   */
  GoalsCalculator createThreadSafeReference();

  /**
   * @param xiState - state to calculate goals in
   * @param role - role for which the score is required
   * @return goal value for specified role in specified state
   */
  int getGoalValue(ForwardDeadReckonInternalMachineState xiState, Role role);
}
