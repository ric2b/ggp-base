
package org.ggp.base.util.propnet.polymorphic.runtimeOptimized;

import java.io.IOException;
import java.io.Writer;

import org.ggp.base.util.propnet.polymorphic.PolymorphicNot;
import org.ggp.base.util.propnet.polymorphic.bidirectionalPropagation.BidirectionalPropagationComponent;

/**
 * The Not class is designed to represent logical NOT gates.
 */
@SuppressWarnings("serial")
public final class RuntimeOptimizedNot extends RuntimeOptimizedComponent
                                                                        implements
                                                                        PolymorphicNot
{
  public RuntimeOptimizedNot(int numOutput)
  {
    super(1, numOutput);
  }

  /**
   * Returns the inverse of the input to the not.
   *
   * @see org.ggp.base.util.propnet.architecture.Component#getValueInternal()
   */
  @Override
  protected boolean getValueInternal()
  {
    return !singleInput.getValue();
  }

  @Override
  public void setDirty(boolean from, BidirectionalPropagationComponent source)
  {
    if (!dirty)
    {
      dirty = true;

      for (RuntimeOptimizedComponent output : outputsArray)
      {
        output.setDirty(cachedValue, this);
      }
    }
  }

  @Override
  public void setKnownChangedState(boolean newState,
                                   BidirectionalPropagationComponent source)
  {
    dirty = false;
    cachedValue = !newState;

    for (RuntimeOptimizedComponent output : outputsArray)
    {
      output.setKnownChangedState(newState, this);
    }
  }

  @Override
  public String toString()
  {
    return "NOT";
  }

  @Override
  public void renderAsDot(Writer xiOutput) throws IOException
  {
    renderAsDot(xiOutput, "invtriangle", "grey", "NOT");
  }
}