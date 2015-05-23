
package org.ggp.base.util.propnet.polymorphic.runtimeOptimized;

import java.io.IOException;
import java.io.Writer;

import org.ggp.base.util.propnet.polymorphic.PolymorphicAnd;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.bidirectionalPropagation.BidirectionalPropagationComponent;


/**
 * The And class is designed to represent logical AND gates.
 */
@SuppressWarnings("serial")
public final class RuntimeOptimizedAnd extends RuntimeOptimizedComponent implements PolymorphicAnd
{
  private PolymorphicComponent knownFalseInput = null;

  public RuntimeOptimizedAnd(int numInputs, int numOutput)
  {
    super(numInputs, numOutput);
  }

  /**
   * Returns true if and only if every input to the and is true.
   *
   * @see org.ggp.base.util.propnet.architecture.Component#getValueInternal()
   */
  @Override
  protected boolean getValueInternal()
  {
    boolean dirtyFound = false;

    knownFalseInput = null;
    //	See if we can find a result without further queries first by checking
    //	non-dirty inputs
    for (RuntimeOptimizedComponent component : inputsArray)
    {
      if (!component.isDirty())
      {
        if (!component.getValue())
        {
          knownFalseInput = component;
          return false;
        }
      }
      else
      {
        dirtyFound = true;
      }
    }

    if (dirtyFound)
    {
      for (RuntimeOptimizedComponent component : inputsArray)
      {
        if (!component.getValue())
        {
          knownFalseInput = component;
          return false;
        }
      }
    }

    return true;
  }

  @Override
  public void reset(boolean disable)
  {
    super.reset(disable);
    knownFalseInput = null;
  }

  void reFindKnownFalse()
  {
    knownFalseInput = null;

    for (RuntimeOptimizedComponent input : inputsArray)
    {
      if (!input.isDirty() && !input.getValue())
      {
        knownFalseInput = input;
        break;
      }
    }
  }

  @Override
  public void setDirty(boolean from, BidirectionalPropagationComponent source)
  {
    if (!dirty)
    {
      if (source == knownFalseInput)
      {
        reFindKnownFalse();
      }

      if (null == knownFalseInput)
      {
        dirty = true;

        for (RuntimeOptimizedComponent output : outputsArray)
        {
          output.setDirty(cachedValue, this);
        }
      }
    }
  }

  @Override
  public void setKnownChangedState(boolean newState,
                                   BidirectionalPropagationComponent source)
  {
    if (!newState)
    {
      if (knownFalseInput == null)
      {
        knownFalseInput = source;
      }
      dirty = false;
      if (cachedValue)
      {
        cachedValue = false;
        for (RuntimeOptimizedComponent output : outputsArray)
        {
          output.setKnownChangedState(true, this);
        }
      }
      return;
    }

    setDirty(true, source);
  }

  @Override
  public String toString()
  {
    return "AND";
  }

  @Override
  public void renderAsDot(Writer xiOutput) throws IOException
  {
    renderAsDot(xiOutput, "invhouse", "grey", "AND");
  }
}
