
package org.ggp.base.util.propnet.polymorphic.learning;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import org.ggp.base.util.propnet.architecture.Component;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicOr;
import org.ggp.base.util.propnet.polymorphic.bidirectionalPropagation.BidirectionalPropagationComponent;
import org.ggp.base.util.propnet.polymorphic.runtimeOptimized.RuntimeOptimizedComponent;

/**
 * The Or class is designed to represent logical OR gates.
 */
@SuppressWarnings("serial")
public final class LearningOr extends LearningComponent implements
                                                       PolymorphicOr
{
  PolymorphicComponent                       knownTrueInput     = null;

  public static int                          numSwaps           = 0;
  public static int                          totalSearchCount   = 0;
  public static int                          numSearches        = 0;

  private Map<PolymorphicComponent, Integer> successSearchCount = null;

  /**
   * Returns true if and only if at least one of the inputs to the or is true.
   * 
   * @see org.ggp.base.util.propnet.architecture.Component#getValueInternal()
   */
  @Override
  protected boolean getValueInternal()
  {
    if (successSearchCount == null)
    {
      successSearchCount = new HashMap<PolymorphicComponent, Integer>();
    }

    for (LearningComponent component : inputs)
    {
      Integer count = 0;

      if (successSearchCount.containsKey(component))
      {
        count = successSearchCount.get(component);
      }

      EncapsulatedCost cost = new EncapsulatedCost();
      if (component.getValueAndCost(cost))
      {
        successSearchCount.put(component,
                               count - cost.getCost() + inputs.size() / 2);
      }
      else
      {
        successSearchCount.put(component, count - cost.getCost());
      }
    }

    boolean dirtyFound = false;

    knownTrueInput = null;
    //	See if we can find a result without further queries first by checking
    //	non-dirty inputs
    for (LearningComponent component : inputs)
    {
      if (!component.isDirty())
      {
        if (component.getValue())
        {
          knownTrueInput = component;
          return true;
        }
      }
      else
      {
        dirtyFound = true;
      }
    }

    if (dirtyFound)
    {
      numSearches++;

      for (LearningComponent component : inputs)
      {
        totalSearchCount++;

        if (component.getValue())
        {
          numSwaps++;
          knownTrueInput = component;
          return true;
        }
      }
    }

    return false;
  }

  void refindKnownTrues()
  {
    knownTrueInput = null;

    for (LearningComponent input : inputs)
    {
      if (!input.isDirty() && input.getValue())
      {
        knownTrueInput = input;
        break;
      }
    }
  }

  protected boolean getValueAndCost(EncapsulatedCost aggregatedCost)
  {
    boolean dirtyFound = false;

    aggregatedCost.incrementCost();

    if (dirty)
    {
      //	See if we can find a result without further queries first by checking
      //	non-dirty inputs
      for (LearningComponent component : inputs)
      {
        if (!component.isDirty())
        {
          if (component.getValueAndCost(aggregatedCost))
          {
            return true;
          }
        }
        else
        {
          dirtyFound = true;
        }
      }

      if (dirtyFound)
      {
        for (LearningComponent component : inputs)
        {
          if (component.getValueAndCost(aggregatedCost))
          {
            return true;
          }
        }
      }

      return false;
    }
    else
    {
      return cachedValue;
    }
  }

  @Override
  public void Optimize()
  {
    if (successSearchCount != null)
    {
      LinkedList<LearningComponent> newInputs = new LinkedList<LearningComponent>();

      for (LearningComponent c : inputs)
      {
        Integer count = 0;

        if (successSearchCount.containsKey(c))
        {
          count = successSearchCount.get(c);
        }
        else
        {
          successSearchCount.put(c, 0);
        }

        int i;
        for (i = 0; i < newInputs.size(); i++)
        {
          if (count > successSearchCount.get(newInputs.get(i)))
          {
            break;
          }
        }

        if (i < newInputs.size())
        {
          newInputs.add(i, c);
        }
        else
        {
          newInputs.add(c);
        }
      }

      inputs = newInputs;
      successSearchCount = null;
    }
  }

  @Override
  public void reset(boolean disable)
  {
    super.reset(disable);
    knownTrueInput = null;
  }

  @Override
  public void setDirty(boolean from, BidirectionalPropagationComponent source)
  {
    dirtyCount++;

    if (!source.isDirty())
    {
      if (!from)
      {
        if (knownTrueInput == null)
        {
          knownTrueInput = source;
        }
        dirty = false;
        if (!cachedValue)
        {
          cachedValue = true;
          for (LearningComponent output : outputs)
          {
            output.setDirty(false, this);
          }
        }
        return;
      }
    }

    if (!dirty)
    {
      if (source == knownTrueInput)
      {
        refindKnownTrues();
      }

      if (null == knownTrueInput)
      {
        dirty = true;

        for (LearningComponent output : outputs)
        {
          output.setDirty(cachedValue, this);
        }
      }
    }
  }

  /**
   * @see org.ggp.base.util.propnet.architecture.Component#toString()
   */
  @Override
  public String toString()
  {
    return toDot("ellipse", "grey", "OR");
  }
}