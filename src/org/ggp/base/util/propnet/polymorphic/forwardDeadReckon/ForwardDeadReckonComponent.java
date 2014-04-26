
package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

import org.ggp.base.util.propnet.polymorphic.MultiInstanceComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;

public abstract class ForwardDeadReckonComponent implements
                                                PolymorphicComponent,
                                                MultiInstanceComponent,
                                                Serializable
{
  private static final long               serialVersionUID   = 352527628564121134L;
  /** The inputs to the component. */
  protected ForwardDeadReckonComponent[]  inputsArray        = null;
  protected ForwardDeadReckonComponent    singleInput        = null;
  /** The outputs of the component. */
  protected ForwardDeadReckonComponent[]  outputsArray       = null;

  protected ForwardDeadReckonPropNet      propNet            = null;

  protected int                           inputIndex         = 0;
  protected int                           outputIndex        = 0;

  private Set<ForwardDeadReckonComponent> inputsList;
  private Set<ForwardDeadReckonComponent> outputsList;

  //	Empirically the overhead of queuing exceeds the overhead of
  //	requiring slightly more propagation calls to reach equilibrium - for
  //	games tested the extra calls are a tiny percentage
  protected final static boolean          queuePropagation   = false;

  //protected boolean dirty;
  protected int[]                         state;
  protected final int                     cachedStateMask = 1<<31;
  protected final int                     lastPropagatedStateMask = 1<<30;
  protected final int                     opaqueValueMask = ~(cachedStateMask | lastPropagatedStateMask);

  public static int                       numPropagates      = 0;
  public static int                       numGatesPropagated = 0;
  public boolean[]                        hasQueuedForPropagation;

  public int                              id;

  private long                            signature          = 0;

  /**
   * Creates a new Component with no inputs or outputs.
   */
  public ForwardDeadReckonComponent(int numInputs, int numOutputs)
  {
    if (numInputs < 0 || numOutputs < 0)
    {
      inputsArray = null;
      inputsList = new HashSet<ForwardDeadReckonComponent>();
      outputsArray = null;
      outputsList = new HashSet<ForwardDeadReckonComponent>();
    }
    else
    {
      inputsArray = new ForwardDeadReckonComponent[numInputs];
      inputsList = null;
      outputsArray = new ForwardDeadReckonComponent[numOutputs];
      outputsList = null;
    }

    state = new int[1];
    if ( queuePropagation )
    {
      hasQueuedForPropagation = new boolean[1];
    }
  }

  @Override
  public void crystalize()
  {
    if (inputsList != null)
    {
      inputsArray = new ForwardDeadReckonComponent[inputsList.size()];

      int index = 0;
      for (ForwardDeadReckonComponent c : inputsList)
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
      outputsArray = new ForwardDeadReckonComponent[outputsList.size()];

      int index = 0;
      for (ForwardDeadReckonComponent c : outputsList)
      {
        outputsArray[index++] = c;
      }

      outputsList = null;
      outputIndex = index;
    }
  }

  @Override
  public void crystalize(int numInstances)
  {
    crystalize();

    state = new int[numInstances];
    if ( queuePropagation )
    {
      hasQueuedForPropagation = new boolean[numInstances];
    }
  }

  @Override
  public void removeInput(PolymorphicComponent input)
  {
    if (inputsList != null)
    {
      inputsList.remove(input);
      if (singleInput == input)
      {
        singleInput = (inputsList.size() == 0 ? null : inputsList.iterator()
            .next());
      }
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
      singleInput = null;
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

  public void setPropnet(ForwardDeadReckonPropNet propNet)
  {
    this.propNet = propNet;
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
      inputsList.add((ForwardDeadReckonComponent)input);

      if (singleInput == null)
      {
        singleInput = (ForwardDeadReckonComponent)input;
      }
    }
    else
    {
      if (inputIndex == 0)
      {
        singleInput = (ForwardDeadReckonComponent)input;
      }
      inputsArray[inputIndex++] = (ForwardDeadReckonComponent)input;
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
      outputsList.add((ForwardDeadReckonComponent)output);
    }
    else
    {
      outputsArray[outputIndex++] = (ForwardDeadReckonComponent)output;
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
    LinkedList<ForwardDeadReckonComponent> result = new LinkedList<ForwardDeadReckonComponent>();

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

    LinkedList<ForwardDeadReckonComponent> result = new LinkedList<ForwardDeadReckonComponent>();

    for (ForwardDeadReckonComponent c : outputsArray)
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
      return outputsList.iterator().next();
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
    return (state[0] & cachedStateMask) != 0;
  }

  /**
   * Gets the value of the Component.
   *
   * @return The value of the Component.
   */
  @Override
  public boolean getValue(int instanceId)
  {
    return (state[instanceId] & cachedStateMask) != 0;
  }

  public boolean getLastPropagatedValue(int instanceId)
  {
    return (state[instanceId] & lastPropagatedStateMask) != 0;
  }

  public void validate()
  {
    for (int instanceId = 0; instanceId < state.length; instanceId++)
    {
      if (inputIndex == 1 &&
          !(getSingleInput() instanceof PolymorphicTransition))
      {
        if (((state[instanceId] & cachedStateMask) != 0) != inputsArray[0]
            .getLastPropagatedValue(instanceId))
        {
          System.out.println("Validation failure for " + toString());
        }
      }
    }
  }

  public void queuePropagation(int instanceId)
  {
    //for(ForwardDeadReckonComponent output : outputsArray)
    //{
    //	output.validate();
    //}
    int stateVal = state[instanceId];
    if (((stateVal & lastPropagatedStateMask) != 0) != ((stateVal & cachedStateMask) != 0))
    {
      if (!hasQueuedForPropagation[instanceId] && outputIndex > 0)
      {
        hasQueuedForPropagation[instanceId] = true;
        //numGatesPropagated++;

        propNet.addToPropagateQueue(this, instanceId);
      }
    }
  }

  public void propagate(int instanceId)
  {
    //for(ForwardDeadReckonComponent output : outputsArray)
    //{
    //	output.validate();
    //}

    //System.out.println("Component " + Integer.toHexString(hashCode()) + " changing from " + lastPropagatedValue + " to " + cachedValue);
    int stateVal = state[instanceId];
    boolean cachedVal = ((stateVal & cachedStateMask) != 0);
    if (((stateVal & lastPropagatedStateMask) != 0) != cachedVal)
    {
      //numPropagates++;
      for (ForwardDeadReckonComponent output : outputsArray)
      {
        output.setKnownChangedState(cachedVal, instanceId, this);
      }

      if ( cachedVal )
      {
        state[instanceId] |= lastPropagatedStateMask;
      }
      else
      {
        state[instanceId] &= ~lastPropagatedStateMask;
      }
    }

    if ( queuePropagation )
    {
      hasQueuedForPropagation[instanceId] = false;
    }

    //for(ForwardDeadReckonComponent output : outputsArray)
    //{
    //	output.validate();
    //}
  }

  public abstract void setKnownChangedState(boolean newState,
                                            int instanceId,
                                            ForwardDeadReckonComponent source);

  public void reset(int instanceId)
  {
    state[instanceId] = 0;
    if ( queuePropagation )
    {
      hasQueuedForPropagation[instanceId] = false;
    }
  }

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
