
package org.ggp.base.util.propnet.polymorphic.runtimeOptimized;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.ggp.base.util.propnet.architecture.Component;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicConstant;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;
import org.ggp.base.util.propnet.polymorphic.bidirectionalPropagation.BidirectionalPropagationComponent;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonComponent;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropNet;

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
      inputsList = new LinkedList<RuntimeOptimizedComponent>();
    }
    else
    {
      inputsArray = new RuntimeOptimizedComponent[numInputs];
      inputsList = null;
    }
    if (numOutputs < 0)
    {
      outputsArray = null;
      outputsList = new LinkedList<RuntimeOptimizedComponent>();
    }
    else
    {
      outputsArray = new RuntimeOptimizedComponent[numOutputs];
      outputsList = null;
    }
  }

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
  public Collection<? extends PolymorphicComponent> getInputs()
  {
    if (inputsArray == null)
    {
      return inputsList;
    }
    else
    {
      LinkedList<RuntimeOptimizedComponent> result = new LinkedList<RuntimeOptimizedComponent>();

      for (int i = 0; i < inputIndex; i++)
      {
        result.add(inputsArray[i]);
      }

      return result;
    }
  }

  /**
   * A convenience method, to get a single input. To be used only when the
   * component is known to have exactly one input.
   * 
   * @return The single input to the component.
   */
  public PolymorphicComponent getSingleInput()
  {
    return singleInput;
  }

  /**
   * Getter method.
   * 
   * @return The outputs of the component.
   */
  public Collection<? extends PolymorphicComponent> getOutputs()
  {
    if (outputsArray == null)
    {
      return outputsList;
    }
    else
    {
      LinkedList<RuntimeOptimizedComponent> result = new LinkedList<RuntimeOptimizedComponent>();

      for (RuntimeOptimizedComponent c : outputsArray)
      {
        result.add(c);
      }

      return result;
    }
  }

  /**
   * A convenience method, to get a single output. To be used only when the
   * component is known to have exactly one output.
   * 
   * @return The single output to the component.
   */
  public PolymorphicComponent getSingleOutput()
  {
    if (outputsArray == null)
    {
      return outputsList.get(0);
    }
    else
    {
      return outputsArray[0];
    }
  }


  /**
   * Gets the value of the Component.
   * 
   * @return The value of the Component.
   */
  public boolean getValue()
  {
    if (dirty)
    {
      dirty = false;
      cachedValue = getValueInternal();
    }

    return cachedValue;
  }

  public boolean isDirty()
  {
    return dirty;
  }

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

  public void setSignature(long signature)
  {
    this.signature = signature;
  }

  public long getSignature()
  {
    return signature;
  }
}
