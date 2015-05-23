
package org.ggp.base.util.propnet.polymorphic.runtimeOptimized;

import java.io.IOException;
import java.io.Writer;

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

  @Override
  public void setDirty(boolean from, BidirectionalPropagationComponent source)
  {
    dirty = true;
  }

  @Override
  public void setKnownChangedState(boolean newState,
                                   BidirectionalPropagationComponent source)
  {
    dirty = false;
    cachedValue = newState;
  }

  @Override
  public String toString()
  {
    return "TRANSITION";
  }

  @Override
  public void renderAsDot(Writer xiOutput) throws IOException
  {
    renderAsDot(xiOutput, "box", "grey", "TRANSITION");
  }
}