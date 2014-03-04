
package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

import org.ggp.base.util.propnet.architecture.Component;
import org.ggp.base.util.propnet.polymorphic.PolymorphicAnd;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.bidirectionalPropagation.BidirectionalPropagationComponent;


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

  public void setKnownChangedState(boolean newState,
                                   int instanceId,
                                   ForwardDeadReckonComponent source)
  {
    if (newState)
    {
      falseInputCount[instanceId]--;
    }
    else
    {
      falseInputCount[instanceId]++;
    }
    //System.out.println("AND " + Integer.toHexString(hashCode()) + " with value " + cachedValue + " received new input " + newState + ", causing false count to become " + falseInputCount);

    if (cachedValue[instanceId] != (falseInputCount[instanceId] == 0))
    {
      cachedValue[instanceId] = (falseInputCount[instanceId] == 0);
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
