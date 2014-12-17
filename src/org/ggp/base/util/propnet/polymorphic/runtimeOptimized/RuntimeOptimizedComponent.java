
package org.ggp.base.util.propnet.polymorphic.runtimeOptimized;

import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicConstant;
import org.ggp.base.util.propnet.polymorphic.bidirectionalPropagation.BidirectionalPropagationComponent;

public abstract class RuntimeOptimizedComponent extends
                                               BidirectionalPropagationComponent
                                                                                implements
                                                                                Serializable
{
  private static final long               serialVersionUID = 352527628564121134L;
  /** The inputs to the component. */
  protected RuntimeOptimizedComponent[]   inputsArray      = null;
  protected RuntimeOptimizedComponent     singleInput      = null;
  /** The outputs of the component. */
  protected RuntimeOptimizedComponent[]   outputsArray     = null;

  protected int                           inputIndex       = 0;
  private int                             outputIndex      = 0;

  private List<RuntimeOptimizedComponent> inputsList;
  private List<RuntimeOptimizedComponent> outputsList;

  protected boolean                       dirty;
  protected boolean                       cachedValue;

  private long                            signature        = 0;

  /**
   * Creates a new Component with no inputs or outputs.
   */
  public RuntimeOptimizedComponent(int numInputs, int numOutputs)
  {
    if (numInputs < 0)
    {
      inputsArray = null;
      inputsList = new LinkedList<>();
    }
    else
    {
      inputsArray = new RuntimeOptimizedComponent[numInputs];
      inputsList = null;
    }
    if (numOutputs < 0)
    {
      outputsArray = null;
      outputsList = new LinkedList<>();
    }
    else
    {
      outputsArray = new RuntimeOptimizedComponent[numOutputs];
      outputsList = null;
    }
  }

  @Override
  public void crystalize()
  {
    if (inputsList != null)
    {
      inputsArray = new RuntimeOptimizedComponent[inputsList.size()];

      int index = 0;
      for (RuntimeOptimizedComponent c : inputsList)
      {
        inputsArray[index++] = c;
      }

      inputsList = null;
      inputIndex = index;
      if (inputIndex > 0)
      {
        singleInput = inputsArray[0];
      }
    }
    if (outputsList != null)
    {
      outputsArray = new RuntimeOptimizedComponent[outputsList.size()];

      int index = 0;
      for (RuntimeOptimizedComponent c : outputsList)
      {
        outputsArray[index++] = c;
      }

      outputsList = null;
      outputIndex = index;
    }
  }

  @Override
  public void removeInput(PolymorphicComponent input)
  {
    if (inputsList != null)
    {
      inputsList.remove(input);
    }
    else
    {
      throw new RuntimeException("Attempt to manipuate crystalized component");
    }
  }

  @Override
  public void removeAllInputs()
  {
    if (inputsList != null)
    {
      inputsList.clear();
    }
    else
    {
      throw new RuntimeException("Attempt to manipuate crystalized component");
    }
  }

  @Override
  public void removeAllOutputs()
  {
    if (outputsList != null)
    {
      outputsList.clear();
    }
    else
    {
      throw new RuntimeException("Attempt to manipuate crystalized component");
    }
  }

  @Override
  public void removeOutput(PolymorphicComponent output)
  {
    if (outputsList != null)
    {
      outputsList.remove(output);
    }
    else
    {
      throw new RuntimeException("Attempt to manipuate crystalized component");
    }
  }

  /**
   * Adds a new input.
   *
   * @param input
   *          A new input.
   */
  @Override
  public void addInput(PolymorphicComponent input)
  {
    if (inputsArray == null)
    {
      inputsList.add((RuntimeOptimizedComponent)input);

      if (singleInput == null)
      {
        singleInput = (RuntimeOptimizedComponent)input;
      }
    }
    else
    {
      if (inputIndex == 0)
      {
        singleInput = (RuntimeOptimizedComponent)input;
      }
      inputsArray[inputIndex++] = (RuntimeOptimizedComponent)input;
    }
  }

  /**
   * Adds a new output.
   *
   * @param output
   *          A new output.
   */
  @Override
  public void addOutput(PolymorphicComponent output)
  {
    if (outputsArray == null)
    {
      outputsList.add((RuntimeOptimizedComponent)output);
    }
    else
    {
      outputsArray[outputIndex++] = (RuntimeOptimizedComponent)output;
    }
  }

  /**
   * Getter method.
   *
   * @return The inputs to the component. Note this should be rarely used in
   *         the finalized state of the propnet
   */
  @Override
  public Collection<? extends PolymorphicComponent> getInputs()
  {
    if (inputsArray == null)
    {
      return inputsList;
    }
    LinkedList<RuntimeOptimizedComponent> result = new LinkedList<>();

    for (int i = 0; i < inputIndex; i++)
    {
      result.add(inputsArray[i]);
    }

    return result;
  }

  /**
   * A convenience method, to get a single input. To be used only when the
   * component is known to have exactly one input.
   *
   * @return The single input to the component.
   */
  @Override
  public PolymorphicComponent getSingleInput()
  {
    return singleInput;
  }

  /**
   * Getter method.
   *
   * @return The outputs of the component.
   */
  @Override
  public Collection<? extends PolymorphicComponent> getOutputs()
  {
    if (outputsArray == null)
    {
      return outputsList;
    }
    LinkedList<RuntimeOptimizedComponent> result = new LinkedList<>();

    for (RuntimeOptimizedComponent c : outputsArray)
    {
      result.add(c);
    }

    return result;
  }

  /**
   * A convenience method, to get a single output. To be used only when the
   * component is known to have exactly one output.
   *
   * @return The single output to the component.
   */
  @Override
  public PolymorphicComponent getSingleOutput()
  {
    if (outputsArray == null)
    {
      return outputsList.get(0);
    }
    return outputsArray[0];
  }


  /**
   * Gets the value of the Component.
   *
   * @return The value of the Component.
   */
  @Override
  public boolean getValue()
  {
    if (dirty)
    {
      dirty = false;
      cachedValue = getValueInternal();
    }

    return cachedValue;
  }

  @Override
  public boolean isDirty()
  {
    return dirty;
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

  public void setKnownChangedState(boolean newState,
                                   BidirectionalPropagationComponent source)
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
  public void reset(boolean disable)
  {
    if (this instanceof PolymorphicConstant)
    {
      dirty = false;
      cachedValue = getValueInternal();
    }
    else
    {
      if (disable)
      {
        dirty = false;
        cachedValue = false;
      }
      else
      {
        dirty = true;
      }
    }
  }

  /**
   * Calculates the value of the Component.
   *
   * @return The value of the Component.
   */
  @Override
  protected abstract boolean getValueInternal();

  /**
   * Returns a configurable representation of the Component in .dot format.
   *
   * @param shape
   *          The value to use as the <tt>shape</tt> attribute.
   * @param fillcolor
   *          The value to use as the <tt>fillcolor</tt> attribute.
   * @param label
   *          The value to use as the <tt>label</tt> attribute.
   * @return A representation of the Component in .dot format.
   */
  protected String toDot(String shape, String fillcolor, String label)
  {
    StringBuilder sb = new StringBuilder();

    sb.append("\"@" + Integer.toHexString(hashCode()) + "\"[shape=" + shape +
              ", style= filled, fillcolor=" + fillcolor + ", label=\"" +
              label + "\"]; ");
    for (PolymorphicComponent component : getOutputs())
    {
      sb.append("\"@" + Integer.toHexString(hashCode()) + "\"->" + "\"@" +
                Integer.toHexString(component.hashCode()) + "\"; ");
    }

    return sb.toString();
  }

  @Override
  public void setSignature(long signature)
  {
    this.signature = signature;
  }

  @Override
  public long getSignature()
  {
    return signature;
  }
}
