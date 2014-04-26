
package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import org.ggp.base.util.propnet.polymorphic.PolymorphicAnd;


/**
 * The And class is designed to represent logical AND gates.
 */
@SuppressWarnings("serial")
public final class ForwardDeadReckonAnd extends ForwardDeadReckonComponent
                                                                          implements
                                                                          PolymorphicAnd
{
  public ForwardDeadReckonAnd(int numInputs, int numOutput)
  {
    super(numInputs, numOutput);
  }

  @Override
  public void reset(int instanceId)
  {
    super.reset(instanceId);
    state[instanceId] = inputsArray.length;
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

    if (newState)
    {
      stateVal = --state[instanceId];
    }
    else
    {
      stateVal = ++state[instanceId];
    }
    //System.out.println("AND " + Integer.toHexString(hashCode()) + " with value " + cachedValue + " received new input " + newState + ", causing false count to become " + falseInputCount);

    boolean countIsZero = ((stateVal & opaqueValueMask) == 0);
    if (((state[instanceId] & cachedStateMask) != 0) != countIsZero)
    {
      if ( countIsZero )
      {
        state[instanceId] |= cachedStateMask;
      }
      else
      {
        state[instanceId] &= ~cachedStateMask;
      }
       //System.out.println("AND value set to "+ cachedValue);

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
  public void validate()
  {
    for (int instanceId = 0; instanceId < state.length; instanceId++)
    {
      int falseInputCount = 0;

      for (ForwardDeadReckonComponent c : inputsArray)
      {
        if (!c.getLastPropagatedValue(instanceId))
        {
          falseInputCount++;
          break;
        }
      }

      if ((falseInputCount == 0) != ((state[instanceId] & cachedStateMask) != 0))
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
    return toDot("invhouse", "grey", "AND");
  }

}
