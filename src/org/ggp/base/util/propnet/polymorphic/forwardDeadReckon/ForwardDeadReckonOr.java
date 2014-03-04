
package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import org.ggp.base.util.propnet.architecture.Component;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicOr;
import org.ggp.base.util.propnet.polymorphic.bidirectionalPropagation.BidirectionalPropagationComponent;

/**
 * The Or class is designed to represent logical OR gates.
 */
@SuppressWarnings("serial")
public final class ForwardDeadReckonOr extends ForwardDeadReckonComponent
                                                                         implements
                                                                         PolymorphicOr
{
  int[] trueInputCount;

  public ForwardDeadReckonOr(int numInputs, int numOutputs)
  {
    super(numInputs, numOutputs);

    trueInputCount = new int[1];
    trueInputCount[0] = 0;
  }

  public void reset(int instanceId)
  {
    super.reset(instanceId);
    cachedValue[instanceId] = false;
    trueInputCount[instanceId] = 0;
  }

  @Override
  public void crystalize(int numInstances)
  {
    super.crystalize(numInstances);

    trueInputCount = new int[numInstances];
  }

  public void setKnownChangedState(boolean newState,
                                   int instanceId,
                                   ForwardDeadReckonComponent source)
  {
    //validate();

    if (newState)
    {
      trueInputCount[instanceId]++;
    }
    else
    {
      trueInputCount[instanceId]--;
    }

    if (cachedValue[instanceId] != (trueInputCount[instanceId] != 0))
    {
      cachedValue[instanceId] = (trueInputCount[instanceId] != 0);

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
      int trueInputCount = 0;

      for (ForwardDeadReckonComponent c : inputsArray)
      {
        if (c.getLastPropagatedValue(instanceId))
        {
          trueInputCount++;
          break;
        }
      }

      if ((trueInputCount != 0) != cachedValue[instanceId])
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