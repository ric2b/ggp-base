
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
  private int[] falseInputCount;

  public ForwardDeadReckonAnd(int numInputs, int numOutput)
  {
    super(numInputs, numOutput);
  }

  @Override
  public void reset(int instanceId)
  {
    super.reset(instanceId);
    cachedValue[instanceId] = false;
    falseInputCount[instanceId] = inputsArray.length;
  }

  @Override
  public void crystalize(int numInstances)
  {
    super.crystalize(numInstances);

    falseInputCount = new int[numInstances];
  }

  @Override
  public void setKnownChangedState(boolean newState,
                                   int instanceId,
                                   ForwardDeadReckonComponent source)
  {
    int count;

    if (newState)
    {
      count = --falseInputCount[instanceId];
    }
    else
    {
      count = ++falseInputCount[instanceId];
    }
    //System.out.println("AND " + Integer.toHexString(hashCode()) + " with value " + cachedValue + " received new input " + newState + ", causing false count to become " + falseInputCount);

    if (cachedValue[instanceId] != (count == 0))
    {
      cachedValue[instanceId] = (count == 0);
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
    for (int instanceId = 0; instanceId < cachedValue.length; instanceId++)
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

      if ((falseInputCount == 0) != cachedValue[instanceId])
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
