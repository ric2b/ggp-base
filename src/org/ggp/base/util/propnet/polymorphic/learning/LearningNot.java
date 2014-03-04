
package org.ggp.base.util.propnet.polymorphic.learning;

import org.ggp.base.util.propnet.polymorphic.PolymorphicNot;
import org.ggp.base.util.propnet.polymorphic.bidirectionalPropagation.BidirectionalPropagationComponent;
import org.ggp.base.util.propnet.polymorphic.runtimeOptimized.RuntimeOptimizedComponent;

/**
 * The Not class is designed to represent logical NOT gates.
 */
@SuppressWarnings("serial")
public final class LearningNot extends LearningComponent implements
                                                        PolymorphicNot
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

  protected boolean getValueAndCost(EncapsulatedCost aggregatedCost)
  {
    aggregatedCost.incrementCost();

    if (dirty)
    {
      return !((LearningComponent)getSingleInput())
          .getValueAndCost(aggregatedCost);
    }
    else
    {
      return cachedValue;
    }
  }

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


  /**
   * @see org.ggp.base.util.propnet.architecture.Component#toString()
   */
  @Override
  public String toString()
  {
    return toDot("invtriangle", "grey", "NOT");
  }
}