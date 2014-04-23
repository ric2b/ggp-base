
package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import org.ggp.base.util.propnet.polymorphic.PolymorphicOr;

/**
 * The Or class is designed to represent logical OR gates.
 */
@SuppressWarnings("serial")
public final class ForwardDeadReckonOr extends ForwardDeadReckonComponent
                                                                         implements
                                                                         PolymorphicOr
{
  public ForwardDeadReckonOr(int numInputs, int numOutputs)
  {
    super(numInputs, numOutputs);
  }

  @Override
  public void reset(int instanceId)
  {
    super.reset(instanceId);
    state[instanceId] = 0;
  }

  @Override
  public void crystalize(int numInstances)
  {
    super.crystalize(numInstances);
  }

  @Override
  public void setKnownChangedState(boolean newState,
                                   int instanceId,
                                   ForwardDeadReckonComponent source)
  {
    int stateVal;
    //validate();

    if (newState)
    {
      stateVal = ++state[instanceId];
    }
    else
    {
      stateVal = --state[instanceId];
    }

    boolean countNonZero = ((stateVal & opaqueValueMask) != 0);
    if (((stateVal & cachedStateMask) != 0) != countNonZero)
    {
      if ( countNonZero )
      {
        state[instanceId] |= cachedStateMask;
      }
      else
      {
        state[instanceId] &= ~cachedStateMask;
      }

      if (queuePropagation)
      {
        queuePropagation(instanceId);
      }
      else
      {
        propagate(instanceId);
      }
    }
  }

  @Override
  public void noteNewValue(int instanceId, boolean value)
  {
  }

  @Override
  public void validate()
  {
    for (int instanceId = 0; instanceId < state.length; instanceId++)
    {
      int trueInputCount = 0;

      for (ForwardDeadReckonComponent c : inputsArray)
      {
        if (c.getLastPropagatedValue(instanceId))
        {
          trueInputCount++;
          break;
        }
      }

      if ((trueInputCount != 0) != ((state[instanceId] & cachedStateMask) != 0))
      {
        System.out.println("Validation failure for " + toString());
      }
    }
  }

  /**
   * @see org.ggp.base.util.propnet.architecture.Component#toString()
   */
  @Override
  public String toString()
  {
    return toDot("ellipse", "grey", "OR");
  }
}