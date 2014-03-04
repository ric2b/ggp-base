
package org.ggp.base.util.propnet.polymorphic.learning;

import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;

/**
 * The Proposition class is designed to represent named latches.
 */
@SuppressWarnings("serial")
public final class LearningProposition extends LearningComponent implements
                                                                PolymorphicProposition
{
  /** The name of the Proposition. */
  private GdlSentence name;
  /** The value of the Proposition. */
  private boolean     value;

  /**
   * Creates a new Proposition with name <tt>name</tt>.
   *
   * @param name
   *          The name of the Proposition.
   */
  public LearningProposition(GdlSentence name)
  {
    this.name = name;
    this.value = false;
  }

  /**
   * Getter method.
   *
   * @return The name of the Proposition.
   */
  public GdlSentence getName()
  {
    return name;
  }

  /**
   * Setter method. This should only be rarely used; the name of a proposition
   * is usually constant over its entire lifetime.
   *
   * @return The name of the Proposition.
   */
  public void setName(GdlSentence newName)
  {
    name = newName;
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
    if (getInputs().size() == 0)
    {
      return value;
    }
    PolymorphicComponent predecessor = getSingleInput();
    if (predecessor instanceof PolymorphicTransition)
    {
      return value;
    }
    return predecessor.getValue();
  }

  protected boolean getValueAndCost(EncapsulatedCost aggregatedCost)
  {
    aggregatedCost.incrementCost();

    if (dirty)
    {
      if (getInputs().size() == 0)
      {
        return value;
      }
      PolymorphicComponent predecessor = getSingleInput();
      if (predecessor instanceof PolymorphicTransition)
      {
        return value;
      }
      return ((LearningComponent)predecessor)
          .getValueAndCost(aggregatedCost);
    }
    return cachedValue;
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
  public void setValue(boolean value)
  {
    if (this.value != value)
    {
      this.value = value;
      cachedValue = value;
      dirty = false;

      for (LearningComponent output : outputs)
      {
        output.setDirty(!value, this);
      }
    }
  }

  /**
   * @see org.ggp.base.util.propnet.architecture.Component#toString()
   */
  @Override
  public String toString()
  {
    return toDot("circle", value ? "red" : "white", name.toString());
  }
}