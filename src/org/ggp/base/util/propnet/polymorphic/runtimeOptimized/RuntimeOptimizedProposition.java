
package org.ggp.base.util.propnet.polymorphic.runtimeOptimized;

import java.io.IOException;
import java.io.Writer;

import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;
import org.ggp.base.util.propnet.polymorphic.bidirectionalPropagation.BidirectionalPropagationComponent;

/**
 * The Proposition class is designed to represent named latches.
 */
@SuppressWarnings("serial")
public final class RuntimeOptimizedProposition extends RuntimeOptimizedComponent implements PolymorphicProposition
{
  /** The name of the Proposition. */
  private GdlSentence           name;
  /** The value of the Proposition. */
  private boolean               value;
  private PolymorphicTransition predecessorTransition = null;

  /**
   * Creates a new Proposition with name <tt>name</tt>.
   *
   * @param numOutputs
   * @param name
   *          The name of the Proposition.
   */
  public RuntimeOptimizedProposition(int numOutputs, GdlSentence name)
  {
    super(1, numOutputs);

    this.name = name;
    this.value = false;
  }

  /**
   * Getter method.
   *
   * @return The name of the Proposition.
   */
  @Override
  public GdlSentence getName()
  {
    return name;
  }

  /**
   * Setter method. This should only be rarely used; the name of a proposition
   * is usually constant over its entire lifetime.
   */
  @Override
  public void setName(GdlSentence newName)
  {
    name = newName;
  }

  @Override
  public void addInput(PolymorphicComponent input)
  {
    super.addInput(input);

    if (input instanceof PolymorphicTransition)
    {
      predecessorTransition = (PolymorphicTransition)input;
    }
  }

  /**
   * Returns the current value of the Proposition.
   *
   * @see org.ggp.base.util.propnet.architecture.Component#getValueInternal()
   */
  @Override
  protected boolean getValueInternal()
  {
    //	Pass-through for backward propagation in all cases except where predecessor is a transition
    if (inputIndex == 0 || predecessorTransition != null)
    {
      return value;
    }
    return inputsArray[0].getValue();
  }

  @Override
  public void reset(boolean disable)
  {
    if (disable)
    {
      value = false;
    }

    super.reset(disable);
  }

  /**
   * Setter method.
   *
   * @param value
   *          The new value of the Proposition.
   */
  @Override
  public void setValue(boolean value)
  {
    if (this.value != value)
    {
      this.value = value;
      cachedValue = value;
      dirty = false;

      for (RuntimeOptimizedComponent output : outputsArray)
      {
        output.setDirty(!value, this);
      }
    }
  }

  @Override
  public void setKnownChangedState(boolean newState,
                                   BidirectionalPropagationComponent source)
  {
    dirty = false;
    cachedValue = newState;

    for (RuntimeOptimizedComponent output : outputsArray)
    {
      output.setKnownChangedState(newState, this);
    }
  }

  @Override
  public String toString()
  {
    return name.toString() + "(" + value + ")";
  }

  @Override
  public void renderAsDot(Writer xiOutput) throws IOException
  {
    renderAsDot(xiOutput, "circle", value ? "red" : "white", name.toString());
  }
}