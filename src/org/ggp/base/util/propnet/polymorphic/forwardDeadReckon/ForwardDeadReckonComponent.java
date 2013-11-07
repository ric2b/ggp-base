package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

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

public abstract class ForwardDeadReckonComponent implements PolymorphicComponent, Serializable {
	private static final long serialVersionUID = 352527628564121134L;
    /** The inputs to the component. */
    protected ForwardDeadReckonComponent[] inputsArray = null;
    protected ForwardDeadReckonComponent singleInput = null;
    /** The outputs of the component. */
    protected ForwardDeadReckonComponent[] outputsArray = null;
    
    protected ForwardDeadReckonPropNet propNet = null;
    
    protected int inputIndex = 0;
    protected int outputIndex = 0;
    
    private Set<ForwardDeadReckonComponent> inputsList; 
    private Set<ForwardDeadReckonComponent> outputsList;
    
    //	Empirically the overhead of queuing exceeds the overhead of
    //	requiring slightly more propagation calls to reach equilibrium - for
    //	games tested the extra calls are a tiny percentage
    protected final boolean queuePropagation = false;
    
    //protected boolean dirty;
    protected boolean cachedValue;
    protected boolean lastPropagatedValue;
    
    public static int numPropagates = 0;
    public static int numGatesPropagated = 0;
    public boolean hasQueuedForPropagation = false;

    /**
     * Creates a new Component with no inputs or outputs.
     */
    public ForwardDeadReckonComponent(int numInputs, int numOutputs)
    {
    	if ( numInputs < 0 || numOutputs < 0 )
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
    }
	
	public void crystalize()
	{
		if ( inputsList != null )
		{
			inputsArray = new ForwardDeadReckonComponent[inputsList.size()];
			
			int index = 0;
			for(ForwardDeadReckonComponent c : inputsList)
			{
				inputsArray[index++] = c;
			}
			
			inputsList = null;
			inputIndex = index;
			if ( inputIndex > 0 )
			{
				singleInput = inputsArray[0];
			}
		}
		if ( outputsList != null )
		{
			outputsArray = new ForwardDeadReckonComponent[outputsList.size()];
			
			int index = 0;
			for(ForwardDeadReckonComponent c : outputsList)
			{
				outputsArray[index++] = c;
			}
			
			outputsList = null;
			outputIndex = index;
		}
	}

	public void removeInput(PolymorphicComponent input)
	{
		if ( inputsList != null )
		{
			inputsList.remove(input);
			if ( singleInput == input )
			{
				singleInput = (inputsList.size() == 0 ? null : inputsList.iterator().next());
			}
		}
		else
		{
			throw new RuntimeException("Attempt to manipuate crystalized component");
		}
	}

	public void removeAllInputs()
	{
		if ( inputsList != null )
		{
			inputsList.clear();
			singleInput = null;
		}
		else
		{
			throw new RuntimeException("Attempt to manipuate crystalized component");
		}	
	}

	public void removeAllOutputs()
	{
		if ( outputsList != null )
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
		if ( outputsList != null )
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
     *            A new input.
     */
    public void addInput(PolymorphicComponent input)
    {
    	if ( inputsArray == null )
    	{
    		inputsList.add((ForwardDeadReckonComponent)input);
    		
    		if ( singleInput == null )
    		{
    			singleInput = (ForwardDeadReckonComponent)input;
    		}
    	}
    	else
    	{
	    	if ( inputIndex == 0 )
	    	{
	    		singleInput = (ForwardDeadReckonComponent)input;
	    	}
	    	inputsArray[inputIndex++] = (ForwardDeadReckonComponent) input;
    	}
    }

    /**
     * Adds a new output.
     * 
     * @param output
     *            A new output.
     */
    public void addOutput(PolymorphicComponent output)
    {
    	if ( outputsArray == null )
    	{
    		outputsList.add((ForwardDeadReckonComponent)output);
    	}
    	else
    	{
    		outputsArray[outputIndex++] = (ForwardDeadReckonComponent) output;
    	}
    }

    /**
     * Getter method.
     * 
     * @return The inputs to the component.  Note this should be rarely used in the finalized
     * state of the propnet
     */
    public Collection<? extends PolymorphicComponent> getInputs()
    {
    	if ( inputsArray == null )
    	{
    		return inputsList;
    	}
    	else
    	{
	    	LinkedList<ForwardDeadReckonComponent> result = new LinkedList<ForwardDeadReckonComponent>();
	    	
	    	for(int i = 0; i < inputIndex; i++)
	    	{
	    		result.add(inputsArray[i]);
	    	}
    	
	    	return result;
    	}
    }
    
    /**
     * A convenience method, to get a single input.
     * To be used only when the component is known to have
     * exactly one input.
     * 
     * @return The single input to the component.
     */
    public PolymorphicComponent getSingleInput() {
     	return singleInput;
    }    
    
    /**
     * Getter method.
     * 
     * @return The outputs of the component.
     */
    public Collection<? extends PolymorphicComponent> getOutputs()
    {
    	if ( outputsArray == null )
    	{
    		return outputsList;
    	}
    	else
    	{
	    	LinkedList<ForwardDeadReckonComponent> result = new LinkedList<ForwardDeadReckonComponent>();
	    	
	    	for(ForwardDeadReckonComponent c : outputsArray)
	    	{
	    		result.add(c);
	    	}
	    	
	        return result;
    	}
   }
    
    /**
     * A convenience method, to get a single output.
     * To be used only when the component is known to have
     * exactly one output.
     * 
     * @return The single output to the component.
     */
    public PolymorphicComponent getSingleOutput() {
    	if (outputsArray == null)
    	{
    		return outputsList.iterator().next();
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
    public  boolean getValue()
    {
    	return cachedValue;
    }
    
    public boolean getLastPropagatedValue()
    {
    	return lastPropagatedValue;
    }
  
	public void validate()
	{
		if ( inputIndex == 1 && !(getSingleInput() instanceof PolymorphicTransition) )
		{
			if ( cachedValue != inputsArray[0].getLastPropagatedValue())
			{
				System.out.println("Validation failure for " + toString());
			}
		}
	}

    public void queuePropagation()
    {
		//for(ForwardDeadReckonComponent output : outputsArray)
		//{
		//	output.validate();
		//}
    	if ( lastPropagatedValue != cachedValue )
    	{
    		if ( !hasQueuedForPropagation && outputIndex > 0)
    		{
    			hasQueuedForPropagation = true;
    			//numGatesPropagated++;
    			
    			propNet.addToPropagateQueue(this);
    		}
    	}
    }
    
    public void propagate()
    {
 		//for(ForwardDeadReckonComponent output : outputsArray)
		//{
		//	output.validate();
		//}
		
		//System.out.println("Component " + Integer.toHexString(hashCode()) + " changing from " + lastPropagatedValue + " to " + cachedValue);
    	if ( lastPropagatedValue != cachedValue )
    	{
    		//numPropagates++;
			for(ForwardDeadReckonComponent output : outputsArray)
			{
				output.setKnownChangedState(cachedValue, this);
			}
		
			lastPropagatedValue = cachedValue;
    	}
    	
		hasQueuedForPropagation = false;
		
		//for(ForwardDeadReckonComponent output : outputsArray)
		//{
		//	output.validate();
		//}
    }
    
    public abstract void setKnownChangedState(boolean newState, ForwardDeadReckonComponent source);
    
    public void reset()
    {
    	cachedValue = false;
    	lastPropagatedValue = false;
    	hasQueuedForPropagation = false;
    }
    
    /**
     * Returns a configurable representation of the Component in .dot format.
     * 
     * @param shape
     *            The value to use as the <tt>shape</tt> attribute.
     * @param fillcolor
     *            The value to use as the <tt>fillcolor</tt> attribute.
     * @param label
     *            The value to use as the <tt>label</tt> attribute.
     * @return A representation of the Component in .dot format.
     */
    protected String toDot(String shape, String fillcolor, String label)
    {
        StringBuilder sb = new StringBuilder();

        sb.append("\"@" + Integer.toHexString(hashCode()) + "\"[shape=" + shape + ", style= filled, fillcolor=" + fillcolor + ", label=\"" + label + "\"]; ");
        for ( PolymorphicComponent component : getOutputs() )
        {
            sb.append("\"@" + Integer.toHexString(hashCode()) + "\"->" + "\"@" + Integer.toHexString(component.hashCode()) + "\"; ");
        }

        return sb.toString();
    }
}
