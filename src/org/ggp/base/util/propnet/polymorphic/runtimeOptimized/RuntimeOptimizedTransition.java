
package org.ggp.base.util.propnet.polymorphic.runtimeOptimized;

import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;
import org.ggp.base.util.propnet.polymorphic.bidirectionalPropagation.BidirectionalPropagationComponent;

/**
 * The Transition class is designed to represent pass-through gates.
 */
@SuppressWarnings("serial")
public final class RuntimeOptimizedTransition extends
                                             RuntimeOptimizedComponent
                                                                      implements
                                                                      PolymorphicTransition
{
  public RuntimeOptimizedTransition(int numOutputs)
  {
    super(1, numOutputs);
  }

  /**
   * Returns the value of the input to the transition.
   * 
   * @see org.ggp.base.util.propnet.architecture.Component#getValueInternal()
   */
  @Override
  protected boolean getValueInternal()
  {
    return singleInput.getValue();
  }

  public void setDirty(boolean from, BidirectionalPropagationComponent source)
  {
    dirty = true;
  }

  public void setKnownChangedState(boolean newState,
                                   BidirectionalPropagationComponent source)
  {
    dirty = false;
    cachedValue = newState;
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