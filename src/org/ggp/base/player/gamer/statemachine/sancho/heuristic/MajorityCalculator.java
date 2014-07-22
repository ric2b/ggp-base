package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public abstract class MajorityCalculator implements ReversableGoalsCalculator
{
  private static final Logger LOGGER = LogManager.getLogger();

  protected final ForwardDeadReckonPropnetStateMachine stateMachine;
  private boolean                                      learningMode = true;
  private boolean                                      isFixedSum = true;
  private int                                          valueTotal = -1;

  protected MajorityCalculator(ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    stateMachine = xiStateMachine;
  }

  public void endLearning()
  {
    learningMode = false;

    if ( isFixedSum )
    {
      LOGGER.info("Terminal goals are fixed sum with total value: " + valueTotal);
    }
  }

  protected abstract int getCount(ForwardDeadReckonInternalMachineState xiState,
                                  Role role);

  @Override
  public int getGoalValue(ForwardDeadReckonInternalMachineState xiState,
                          Role xiRole)
  {
    int ourValue = 0;
    int theirValue = 0;

    for(Role role : stateMachine.getRoles())
    {
      int value = getCount(xiState, role);

      if ( role.equals(xiRole))
      {
        ourValue = value;

        if ( !learningMode && isFixedSum )
        {
          theirValue = valueTotal - ourValue;
          break;
        }
      }
      else
      {
        theirValue = value;

        if ( !learningMode && isFixedSum )
        {
          ourValue = valueTotal - theirValue;
          break;
        }
      }
    }

    if ( learningMode && isFixedSum )
    {
      int total = ourValue + theirValue;

      if ( valueTotal == -1 )
      {
        valueTotal = total;
      }
      else if ( total != valueTotal )
      {
        learningMode = false;
        isFixedSum = false;
      }
    }

    if ( ourValue > theirValue )
    {
      return 100;
    }
    if ( ourValue < theirValue )
    {
      return 0;
    }
    return 50;
  }
}
