
package org.ggp.base.util.propnet.polymorphic.learning;

import java.io.IOException;
import java.io.Writer;

import org.ggp.base.util.propnet.polymorphic.PolymorphicNot;
import org.ggp.base.util.propnet.polymorphic.bidirectionalPropagation.BidirectionalPropagationComponent;

/**
 * The Not class is designed to represent logical NOT gates.
 */
@SuppressWarnings("serial")
public final class LearningNot extends LearningComponent implements PolymorphicNot
{
  /**
   * Returns the inverse of the input to the not.
   *
   * @see org.ggp.base.util.propnet.architecture.Component#getValueInternal()
   */
  @Override
  protected boolean getValueInternal()
  {
    return !getSingleInput().getValue();
  }

  @Override
  protected boolean getValueAndCost(EncapsulatedCost aggregatedCost)
  {
    aggregatedCost.incrementCost();

    if (dirty)
    {
      return !((LearningComponent)getSingleInput())
          .getValueAndCost(aggregatedCost);
    }
    return cachedValue;
  }

  @Override
  public void setDirty(boolean from, BidirectionalPropagationComponent source)
  {
    if (!source.isDirty())
    {
      dirty = false;
      cachedValue = from;

      for (LearningComponent output : outputs)
      {
        output.setDirty(!cachedValue, this);
      }
    }
    else if (!dirty)
    {
      dirty = true;

      for (LearningComponent output : outputs)
      {
        output.setDirty(cachedValue, this);
      }
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