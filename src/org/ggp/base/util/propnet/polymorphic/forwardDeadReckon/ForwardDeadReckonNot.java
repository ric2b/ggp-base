
package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import org.ggp.base.util.propnet.polymorphic.PolymorphicNot;

/**
 * The Not class is designed to represent logical NOT gates.
 */
@SuppressWarnings("serial")
public final class ForwardDeadReckonNot extends ForwardDeadReckonComponent
                                                                          implements
                                                                          PolymorphicNot
{
  public ForwardDeadReckonNot(int numOutput)
  {
    super(1, numOutput);
  }

  @Override
  public void setKnownChangedState(boolean newState,
                                   int instanceId,
                                   ForwardDeadReckonComponent source)
  {
    if ( !newState )
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

  @Override
  public void noteNewValue(int instanceId, boolean value)
  {
  }

  @Override
  public void reset(int instanceId)
  {
    super.reset(instanceId);
    state[instanceId] |= cachedStateMask;
  }

  @Override
  public void validate()
  {
    for (int instanceId = 0; instanceId < state.length; instanceId++)
    {
      if (((state[instanceId] & cachedStateMask) != 0) != !inputsArray[0]
          .getLastPropagatedValue(instanceId))
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
    return toDot("invtriangle", "grey", "NOT");
  }
}