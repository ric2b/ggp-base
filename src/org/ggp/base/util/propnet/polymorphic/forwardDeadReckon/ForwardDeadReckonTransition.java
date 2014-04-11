
package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;

/**
 * The Transition class is designed to represent pass-through gates.
 */
@SuppressWarnings("serial")
public final class ForwardDeadReckonTransition extends
                                              ForwardDeadReckonComponent
                                                                        implements
                                                                        PolymorphicTransition
{
  private ForwardDeadReckonInternalMachineState[]        owningTransitionInfoSet = null;
  private ForwardDeadReckonPropositionInfo               transitionInfo          = null;

  public ForwardDeadReckonTransition(int numOutputs)
  {
    super(1, numOutputs);
  }

  @Override
  public void setKnownChangedState(boolean newState,
                                   int instanceId,
                                   ForwardDeadReckonComponent source)
  {
    cachedValue[instanceId] = newState;
    if (owningTransitionInfoSet[instanceId] != null)
    {
      //ProfileSection methodSection = new ProfileSection("ForwardDeadReckonTransition.legalStateChange");
      //try
      {
        if (newState)
        {
          owningTransitionInfoSet[instanceId].add(transitionInfo);
        }
        else
        {
          owningTransitionInfoSet[instanceId].remove(transitionInfo);
        }
      }
      //finally
      //{
      //	methodSection.exitScope();
      //}
    }
  }

  public void setTransitionSet(ForwardDeadReckonPropositionInfo transitionInfo,
                               int instanceId,
                               ForwardDeadReckonInternalMachineState owningSet)
  {
    this.owningTransitionInfoSet[instanceId] = owningSet;
    this.transitionInfo = transitionInfo;
  }

  @Override
  public void crystalize(int numInstances)
  {
    super.crystalize(numInstances);

    owningTransitionInfoSet = new ForwardDeadReckonInternalMachineState[numInstances];
  }

  /**
   * @see org.ggp.base.util.propnet.architecture.Component#toString()
   */
  @Override
  public String toString()
  {
    return toDot("box", "grey", "TRANSITION");
  }
}